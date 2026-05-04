# 基本設計書：SummaryRecorder

---

## 1. システムアーキテクチャ

### 1.1 全体構成（3層アーキテクチャ）

```
┌─────────────────────────────────────────────────┐
│                  Presentation                   │
│  MainActivity (Compose) ← MainViewModel (Flow)  │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────┴──────────────────────────┐
│                   Domain                        │
│  RecordingService  │  RetryWorker               │
│  (Foreground)      │  (WorkManager 15min)       │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────┴──────────────────────────┐
│                    Data                         │
│  GaplessRecorder │ Room DB │ GroqApi │ GeminiAPI│
│  (AudioRecord)   │ (SSOT)  │(Retrofit)│ (SDK)  │
└─────────────────────────────────────────────────┘
```

### 1.2 パッケージ構成

```
com.kohei.summaryrecorder/
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt
│   │   ├── ChunkDao.kt
│   │   └── ChunkEntity.kt
│   ├── api/
│   │   └── GroqApiService.kt
│   └── repository/
│       ├── TranscriptionRepository.kt
│       └── SummaryRepository.kt
├── recorder/
│   ├── GaplessRecorder.kt
│   └── WavHeaderWriter.kt
├── service/
│   ├── RecordingService.kt
│   └── RetryWorker.kt
├── viewmodel/
│   └── MainViewModel.kt
└── ui/
    └── MainActivity.kt
```

---

## 2. データ設計

### 2.1 ChunkEntity（Room DB）

```kotlin
@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,          // 録音セッションID (UUID)
    val chunkIndex: Int,            // チャンク連番 (0,1,2,...)
    val filePath: String,           // 音声ファイルパス
    val status: ChunkStatus,        // PENDING / UPLOADING / DONE / FAILED
    val transcriptionText: String?, // 文字起こし結果
    val createdAt: Long,            // 作成日時 (epoch ms)
    val updatedAt: Long             // 更新日時 (epoch ms)
)

enum class ChunkStatus {
    PENDING, UPLOADING, DONE, FAILED
}
```

### 2.2 状態遷移

```
[録音分割]
    │
    ▼
  PENDING ──→ UPLOADING ──→ DONE（文字起こし成功）
                  │              │
                  │              └── 音声ファイル削除
                  ▼
               FAILED（通信エラー等）
                  │
                  ▼
         [WorkManager再送] → UPLOADING → ...
```

### 2.3 自己修復ロジック

```kotlin
// ChunkDao
@Query("UPDATE chunks SET status = 'FAILED', updatedAt = :now WHERE status = 'UPLOADING'")
suspend fun resetStuckUploads(now: Long = System.currentTimeMillis())
```

- Service起動時に必ず呼出
- クラッシュで `UPLOADING` 宙吊り → `FAILED` に戻し再送可能に

---

## 3. 詳細設計

### 3.1 録音エンジン（GaplessRecorder）

#### クラス概要

```kotlin
class GaplessRecorder(
    private val outputDir: File,
    private val chunkDurationBytes: Long = 19L * 1024 * 1024, // 19MB
    private val onChunkComplete: (File) -> Unit
)
```

#### 処理フロー

1. `AudioRecord.getMinBufferSize(16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)` でバッファサイズ取得
   - **判断基準**: 端末固有の最適バッファサイズを使用。固定値より安全。取得失敗時は `ERROR_BAD_VALUE` → 録音中止
2. 一時ファイル作成、ダミーWAVヘッダー（44byte）書込み
3. Coroutine内で `AudioRecord.read(buffer, 0, bufferSize)` ループ
4. 書込みサイズ ≥ 19MB → `Mutex.withLock` 内で新ファイルへ切替
5. 旧ファイルの `seek(0)` でヘッダー確定 → `close()`
6. `onChunkComplete` コールバック発火

#### Mutex排他制御

```kotlin
private val mutex = Mutex()

// 書込みスレッド
mutex.withLock {
    // 現在ファイルへPCM追記
    // 19MB到達時: 新ファイル作成 → 書込み先切替
    // 旧ファイル: seek(0) → ヘッダー上書き → close()
}
```

- 書込みとヘッダー確定を同一ロック内で実行
- データ競合・ヘッダー破損を完全防止

### 3.2 WAVヘッダー（WavHeaderWriter）

```kotlin
object WavHeaderWriter {
    fun writeHeader(
        file: RandomAccessFile,
        dataLength: Long,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    )
}
```

- RIFF/WAVE/fmt/data チャンク構成（44byte固定ヘッダー）
- `seek(0)` でファイルサイズ・データサイズを上書き

### 3.3 Groq API通信（TranscriptionRepository）

```kotlin
class TranscriptionRepository(
    private val apiService: GroqApiService
) {
    suspend fun transcribe(file: File): String
}
```

- `MultipartBody.Part` でファイルストリーム送信
- ファイル全体をメモリに載せない（OOM対策）
- `@Multipart @POST("openai/v1/audio/transcriptions")`

### 3.4 Gemini要約（SummaryRepository）

```kotlin
class SummaryRepository(
    private val generativeModel: GenerativeModel
) {
    suspend fun summarize(combinedText: String): String
}
```

- Google Generative AI SDK 直接利用
- モデル: `gemini-2.0-flash`
- DB全テキスト結合 → 一発送信
- **タイムアウト**: 60秒（長文テキストの要約に時間を要するため、デフォルト30秒から延長）

### 3.5 Foreground Service（RecordingService）

```
┌─────────────────────────────────────────┐
│           RecordingService              │
│                                         │
│  ┌─────────────┐  ┌──────────────────┐  │
│  │録音Coroutine │→│アップロードCoroutine│ │
│  │(GaplessRec) │  │(PENDING→DONE)    │  │
│  └─────────────┘  └──────────────────┘  │
│                                         │
│  on Create:                              │
│    1. resetStuckUploads()                │
│    2. 通知チャネル作成・Foreground通知    │
│    3. WorkManagerスケジュール登録         │
│    4. 【バッテリー最適化】未設定なら       │
│       ACTION_REQUEST_IGNORE_             │
│       BATTERY_OPTIMIZATIONS 発行         │
│                                         │
│  on StartCommand:                       │
│    1. GaplessRecorder 起動              │
│    2. onChunkComplete → DB INSERT       │
│    3. PENDING監視ループ開始             │
│                                         │
│  on StopRecording:                      │
│    1. 現在チャンク確定（seek+close）     │
│    2. 最終チャンクPENDING登録           │
│    3. 全完了待ち → Gemini要約           │
└─────────────────────────────────────────┘
```

### 3.6 WorkManager（RetryWorker）

```kotlin
class RetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val failedChunks = dao.getByStatus(FAILED)
        failedChunks.forEach { retry(it) }
        return Result.success()
    }
}
```

- `PeriodicWorkRequestBuilder`（15分間隔）
- `Constraints`: `NetworkType.CONNECTED`
- FAILED → UPLOADING → DONE/FAILED

### 3.7 リアクティブ要約フロー

```kotlin
// MainViewModel
db.observeBySession(sessionId)
    .map { chunks -> chunks.all { it.status == DONE } }
    .distinctUntilChanged()
    .filter { it }
    .onEach {
        val text = chunks.joinToString("\n") { it.transcriptionText ?: "" }
        val summary = summaryRepo.summarize(text)
        // UIへ表示
        // DB物理削除
        dao.deleteBySession(sessionId)
    }
    .launchIn(viewModelScope)
```

---

## 4. 技術スタック

| 役割 | 技術 | バージョン指針 |
|---|---|---|
| 言語 | Kotlin | 2.0+ |
| UI | Jetpack Compose | BOM 2024+ |
| 非同期 | Coroutines + Flow | 1.8+ |
| DB | Room | 2.6+ |
| 通信(音声) | Retrofit + OkHttp | 2.11+ / 4.12+ |
| 通信(要約) | Google Generative AI SDK | latest |
| バックグラウンド | WorkManager | 2.9+ |
| ビルド | Gradle Kotlin DSL | 8.x |
| minSdk / targetSdk | 31 / 34 | - |
| テスト | JUnit5, MockK, Turbine, Robolectric | - |

---

## 5. API設計

### 5.1 Groq API（Whisper）

```
POST https://api.groq.com/openai/v1/audio/transcriptions
Headers: Authorization: Bearer ${GROQ_API_KEY}
Body:    multipart/form-data
         - file: <wavファイル（ストリーム）>
         - model: "whisper-large-v3"
         - language: "ja"
         - response_format: "json"
```

### 5.2 Gemini API

```
モデル: gemini-2.0-flash
入力: 全文字起こしテキスト（結合）
プロンプト例:
  「以下の文字起こしテキストを要約してください。
    主要なトピック、重要な発言、結論を含めてください。」
```

---

## 6. TDD設計

### 6.1 テスト戦略（ピラミッド）

```
        ╱╲
       ╱UI ╲          Compose Test Rules
      ╱──────╲
     ╱ 結合テスト ╲     Service + DB + API(Mock)
    ╱──────────────╲
   ╱    単体テスト     ╲   各クラス単独（MockK）
  ╱──────────────────────╲
```

### 6.2 Phase別テスト一覧

#### Phase 1: DB・API基盤

| テスト名 | 対象 | テスト内容 | テスト種別 |
|---|---|---|---|
| `ChunkDaoTest` | ChunkDao | CRUD正常系 | 単体 |
| `ChunkDaoStatusTest` | ChunkDao | 状態遷移（PENDING→UPLOADING→DONE/FAILED） | 単体 |
| `ChunkDaoResetTest` | ChunkDao | `resetStuckUploads()` でUPLOADING→FAILED | 単体 |
| `ChunkDaoFlowTest` | ChunkDao | Flow観察で状態変化をリアルタイム検知 | 単体 |
| `GroqApiServiceTest` | GroqApiService | モックサーバーでmultipartリクエストの正当性検証 | 単体 |
| `TranscriptionRepoTest` | TranscriptionRepository | ファイル→モックAPI→テキスト取得の結合 | 単体 |

**使用ツール**: `Room(inMemory)`, `MockWebServer`, `MockK`, `Turbine`

#### Phase 2: 録音エンジン（最重要）

| テスト名 | 対象 | テスト内容 | テスト種別 |
|---|---|---|---|
| `WavHeaderTest` | WavHeaderWriter | ヘッダー44byteの構造検証（RIFF/fmt/data） | 単体 |
| `WavHeaderSeekTest` | WavHeaderWriter | seek(0)後のデータサイズ上書き検証 | 単体 |
| `GaplessRecorderWriteTest` | GaplessRecorder | PCM書込み→WAVファイル生成の正常性 | 単体 |
| `GaplessRecorderSplitTest` | GaplessRecorder | 19MB到達時の自動分割動作 | 単体 |
| `GaplessRecorderMutexTest` | GaplessRecorder | 並行アクセス時の排他制御検証 | 単体 |
| `GaplessRecorderGaplessTest` | GaplessRecorder | 分割前後でデータ欠落ゼロの確認 | 単体 |

**使用ツール**: `Robolectric`（AudioRecord要因）, `MockK`, `kotlinx.coroutines.test`

> **注意**: RobolectricのShadowAudioRecordは完全なPCMエミュレーション不可。Phase 2完了後、**実機（Ace III）での結合テスト**が必須。実機確認項目:
> - 実際のAudioRecordバッファサイズ取得
> - 19MB分割時のギャップレス検証（波形比較）
> - 長時間（≥1h）録音の安定性

#### Phase 3: Foreground Service

| テスト名 | 対象 | テスト内容 | テスト種別 |
|---|---|---|---|
| `RecordingServiceInitTest` | RecordingService | 起動時resetStuckUploads()の呼出確認 | 結合 |
| `RecordingServiceFlowTest` | RecordingService | 録音→分割→PENDING→UPLOADING→DONEの一連フロー | 結合 |
| `RecordingServiceSelfHealTest` | RecordingService | クラッシュ模擬→再起動→FAILED復旧の確認 | 結合 |

**使用ツール**: `Robolectric`, `Room(inMemory)`, `MockK`

#### Phase 4: フェールセーフ

| テスト名 | 対象 | テスト内容 | テスト種別 |
|---|---|---|---|
| `RetryWorkerBasicTest` | RetryWorker | FAILED→UPLOADING→DONEの再送成功 | 単体 |
| `RetryWorkerNetworkTest` | RetryWorker | ネットワーク制約の検証 | 単体 |
| `RetryWorkerIdempotentTest` | RetryWorker | 重複実行の冪等性確認 | 単体 |

**使用ツール**: `WorkManager Test`, `MockK`

#### Phase 5: 要約・UI

| テスト名 | 対象 | テスト内容 | テスト種別 |
|---|---|---|---|
| `SummaryRepoTest` | SummaryRepository | テキスト結合→Gemini呼出→要約取得 | 単体 |
| `SummaryFlowTest` | MainViewModel | 全DONE検知→要約実行→DB削除の結合 | 結合 |
| `MainScreenTest` | MainActivity | Compose UI: 録音ボタン・状態表示・要約表示 | UI |
| `MainScreenStateTest` | MainActivity | 状態変化に応じたUI更新のリアクティブ性 | UI |

**使用ツール**: `Compose Test Rule`, `MockK`, `Turbine`

### 6.3 TDDサイクル（Red-Green-Refactor）

各Phaseの実装は以下の手順で行う：

```
1. [Red]    テストコードを先に書く（失敗確認）
2. [Green]  最小実装でテストを通す
3. [Refactor] リファクタリング（テスト通過を維持）
4. [次へ]   次のテストケースへ
```

#### Mock戦略

| コンポーネント | Mock方法 |
|---|---|
| Groq API | `MockWebServer`（OkHttp） |
| Gemini API | `MockK` で `GenerativeModel` をモック |
| AudioRecord | `MockK` または `Robolectric` シャドウ |
| Room DB | `inMemoryDatabaseBuilder`（実DB） |
| WorkManager | `WorkManagerTestInitHelper` |

---

## 7. 開発ロードマップ

| Phase | 内容 | テストファースト | 成果物 |
|---|---|---|---|
| **1** | 環境構築・DB・API基盤 | ChunkDao全テスト → 実装 → Groq疎通テスト → 実装 | DB層・API層 |
| **2** | 録音エンジン | WavHeader全テスト → 実装 → Recorder全テスト → 実装 | 録音層 |
| **3** | Foreground Service + 即時アップロード | Service全テスト → 実装 | Service層 |
| **4** | フェールセーフ（WorkManager） | Worker全テスト → 実装 | 再送層 |
| **5** | リアクティブ要約 + UI + 実機テスト | 要約テスト → 実装 → UIテスト → 実装 | 全体完成 |

---

## 8. エラー処理一覧

| エラー種別 | 検知方法 | 復旧動作 |
|---|---|---|
| ネットワーク断 | Retrofit例外 → FAILED | WorkManager再送 |
| API認証エラー | HTTP 401 | ログ出力・FAILED（手動対応） |
| API制限(429) | HTTP 429 | FAILED → WorkManager指数バックオフ |
| ストレージ不足 | IOException | 録音停止・通知表示 |
| クラッシュ | 再起動時UPLOADING残留 | `resetStuckUploads()` → FAILED |
| OOM | 大ファイル読込 | ストリーム送信で予防（発生不可） |
