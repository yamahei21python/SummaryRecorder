# UI 要件定義 — SummaryRecorder 3タブ化（最終確定版 rev.3）

> レビュー指摘 計32件（rev.2: 17件 + rev.3: 15件）をすべて反映済み。

---

## 確定した設計決定

| 項目 | 決定内容 |
|------|---------|
| 音声ファイル管理 | 録音停止時にチャンクをWAV結合して1ファイル保存 |
| 要約DB永続化 | `summaries` テーブル新規作成 |
| 一時停止 | GaplessRecorder / RecordingController に追加実装 |
| 件名生成 | 要約API呼び出し時にタイトルも同時生成（AIに両方出力させる） |
| 読みおこし生成 | **Groq** がチャンクごとに逐次生成。チャンク結合テキストを `SummaryEntity.transcriptionText` に保存 |
| 情報パネル | ファイル形式・録音品質・チャンネルの表示は削除 |
| チャンク状態 | どこにも表示しない |
| AI出力形式 | **Gemini** が `responseSchema`（title + summaryText の2フィールド）で構造化出力。パース失敗防止 |

---

## タブ構成

| # | タブ名 | アイコン |
|---|--------|---------|
| 1 | 録音操作 | `Icons.Default.Mic` |
| 2 | 音声確認 | `Icons.Default.PlayCircle` |
| 3 | 要約・読みおこし | `Icons.Default.Article` |

ナビゲーション: `TabRow`（上部）→ `NavigationBar`（下部）に変更。  
タブ3のアイコンには **status=DONE かつ isRead=false** の件数のみバッジ表示（100件以上は `99+` と表示）。

---

## タブ1: 録音操作

### 画面レイアウト

```
┌─────────────────────────────────┐
│  TopAppBar: "SummaryRecorder"   │
├─────────────────────────────────┤
│                                 │
│  ┌───────────────────────────┐  │
│  │        00:00:00           │  │  ← タイマー（録音中は背景が青）
│  │  音量 ▓▓▓░░░░░░░░░       │  │  ← 音量バー（待機中・一時停止中は0固定・グレー）
│  │  空き容量        XX GB    │  │
│  │  録音可能時間   XXX 時間   │  │
│  └───────────────────────────┘  │
│                                 │
│       ┌──────┐  ┌──────┐        │
│       │  □  │  │  ●  │        │
│       │ 停止 │  │ 録音 │        │
│       └──────┘  └──────┘        │
│                                 │
│  🎤 録音中 — バックグラウンド継続  │  ← 録音中のみ表示
├─────────────────────────────────┤
│  [🎤録音操作] [▶音声確認] [📄要約] │
└─────────────────────────────────┘
```

### 状態遷移

```
[待機中]
  → 録音ボタン押下 → sessionId = UUID.randomUUID() 生成 → [録音中]

[録音中]
  → 一時停止ボタン押下 → [一時停止中]
  → 停止ボタン押下    → WAV結合 → SummaryDao.insert(RECORDED) → API呼び出し → [待機中]

[一時停止中]
  → 再開ボタン押下  → [録音中]
  → 停止ボタン押下  → WAV結合 → SummaryDao.insert(RECORDED) → API呼び出し → [待機中]
```

| 状態 | タイマー背景 | 右ボタン | 停止ボタン | 音量バー |
|------|------------|---------|----------|---------|
| 待機中 | グレー | ● 録音（赤）| □ 無効 | 0固定・グレー |
| 録音中 | 青 | ‖ 一時停止（赤）| □ 有効 | リアルタイム |
| 一時停止中 | 暗い青 + タイマー500ms点滅 | ▶ 再開（赤）| □ 有効 | 0固定・グレー |

### 表示項目（情報パネル）

| 項目 | 実装 |
|------|------|
| タイマー | 1秒ごとカウントアップ（一時停止中は停止・点滅）。停止後は0にリセット |
| 音量バー | 録音中のみ AudioProvider からリアルタイム取得。それ以外は0固定でグレー表示 |
| 空き容量 | `StatFs(filesDir)` |
| 録音可能時間 | 空き容量 ÷ (44100×2) bytes/s |

---

## タブ2: 音声確認

### 画面レイアウト

```
┌─────────────────────────────────┐
│  TopAppBar: "音声確認"           │
├─────────────────────────────────┤
│                                 │
│  ← 録音なし時 Empty State →     │
│  🎤 録音がありません              │
│  タブ1から録音を開始してください  │
│                                 │
│  ← 録音あり時（createdAt DESC）→ │
│  ┌─────────────────────────┐    │
│  │ 📁 [タイトル]           │ ⋯  │
│  │ 2023/03/23 00:37   0:15 │    │
│  │ [SUMMARIZING...🔄]       │    │  ← RECORDED/SUMMARIZING 時のみ
│  └─────────────────────────┘    │
│  （リスト…）                     │
│                                 │
│  ○───────────────────────○     │  ← Slider（シークバー）
│  00:00                  00:15  │
│                                 │
│  ↩60 ↩10  ▶/‖  10↪ 60↪ [1.0x] │  ← 速度ボタン（タップで循環トグル）
├─────────────────────────────────┤
│  [🎤録音操作] [▶音声確認] [📄要約] │
└─────────────────────────────────┘
```

### 再生速度トグル（循環式）

タップするたびに以下の順で切り替わる:

```
1.0x → 1.2x → 1.5x → 2.0x → 0.5x → 0.8x → 1.0x → ...
```

- 選択中の速度は `DataStore<Preferences>` の `playback_speed` キー（Float）に保存し、起動時に復元する
- デフォルト値は `1.0f`

### 再生挙動

| イベント | 挙動 |
|---------|------|
| アイテムタップ | 再生開始 |
| シーク（Slider 操作） | `seekTo()` で任意位置にジャンプ |
| 再生完了（末尾到達） | 状態を一時停止に戻し、再生位置を 0 にリセット |
| タブ切り替え / バックグラウンド移行 | `DisposableEffect` or `LifecycleObserver` で `MediaPlayer.pause()` を呼ぶ |
| 再生中アイテムの削除 | `Player.stop()` → `Player.clearMediaItems()` → ファイル削除 の順に実行。`release()` は呼ばない（インスタンスを使い回すため） |

### 機能要件

| 機能 | 実装方法 |
|------|---------|
| 録音一覧 | `SummaryEntity`（全 status）を `createdAt DESC` で表示 |
| RECORDED/SUMMARIZING 表示 | プログレスインジケーターをカードに表示 |
| タップで再生 | `ExoPlayer`（Media3）|
| 10秒/60秒 早送り・巻き戻し | `seekTo(pos ± N * 1000)` |
| 再生速度 | 循環トグルボタン（上記参照）|
| 削除 | ⋯メニュー → 確認ダイアログ → `DeleteSummaryUseCase`。再生中の場合は `Player.stop()` + `Player.clearMediaItems()` を先に呼ぶ。`Player.release()` は ViewModel の `onCleared()` のみで呼ぶ（インスタンスを使い回すため） |
| Empty State | マイクアイコン + 案内テキスト |

---

## タブ3: 要約・読みおこし

### 一覧画面

```
┌─────────────────────────────────┐
│  TopAppBar: "要約・読みおこし"   │
├─────────────────────────────────┤
│                                 │
│  ← 録音なし時 Empty State →     │
│  📄 録音がありません              │
│  タブ1から録音を開始してください  │
│                                 │
│  ← 録音あり時（createdAt DESC）→ │
│  ┌─────────────────────────┐    │
│  │ 📄 [AIが生成したタイトル] │ ⋯  │
│  │  要約プレビュー（1〜2行）  │    │
│  │  2023/03/23 00:37        │    │
│  │  [要約中...🔄]            │    │  ← RECORDED/SUMMARIZING 時
│  │  [❌ エラー] [再試行]     │    │  ← ERROR 時
│  └─────────────────────────┘    │
├─────────────────────────────────┤
│  [🎤録音操作] [▶音声確認] [📄(3)] │  ← DONE かつ isRead=false の件数（最大 99+）
└─────────────────────────────────┘
```

### 詳細画面（タップ後）

```
┌─────────────────────────────────┐
│  ←  [タイトル]          [✏編集] │
├─────────────────────────────────┤
│  [ 要約 ] [ 読みおこし ]         │
│                                 │
│  テキスト（SelectionContainer）  │
└─────────────────────────────────┘
```

- 詳細画面を開いた際（`LaunchedEffect(sessionId)` or ViewModel の `init`）に `SummaryDao.updateRead(sessionId, true)` を呼ぶ
- バッジカウントから自動的に除外される

### ERROR 時の再試行フロー

```
再試行ボタン押下
  → SummaryDao.updateStatus(sessionId, SUMMARIZING)
  → SummarizeUseCase.execute(sessionId)
      ├─ 成功: SummaryDao.updateStatus(sessionId, DONE) + title/summary/transcription 更新
      └─ 失敗: SummaryDao.updateStatus(sessionId, ERROR, errorMessage)
```

### 機能要件

| 機能 | 詳細 |
|------|------|
| 一覧 | `summaries` テーブルを `createdAt DESC` で全件取得 |
| ローディング | RECORDED/SUMMARIZING のカードにプログレスインジケーター |
| タイトル | AIが生成（後から✏で編集可能） |
| 詳細タブ | 要約全文 / 読みおこし全文を切替 |
| コピー | `SelectionContainer` |
| タイトル編集 | ✏ → `AlertDialog(TextField, maxLength=20)` → `SummaryDao.updateTitle()`。20文字超過時は保存ボタンを無効化しUI崩れを防止 |
| 削除 | ⋯ → 確認ダイアログ → `DeleteSummaryUseCase` |
| エラー表示 | ERROR のカードにエラーメッセージ + 再試行ボタン |
| Empty State | アイコン + 案内テキスト |
| 未読バッジ | DONE かつ isRead=false の件数（100件以上は `99+`） |

---

## AI出力仕様（役割分担）

### API役割分担

| API | 担当 | タイミング |
|-----|------|----------|
| **Groq** | 読みおこし（`transcriptionText`） | 録音中、チャンクごとに逐次実行 |
| **Gemini** | タイトル（`title`）+ 要約（`summaryText`） | 録音停止後、全チャンクの読みおこし結合テキストを受け取って1回実行 |

`SummaryEntity.transcriptionText` は、Groqが各チャンクに返した `ChunkEntity.transcriptionText` をチャンク順に結合したものを使用する（Geminiは生成しない）。

---

### `SummaryResult` データクラス

```kotlin
// Gemini が返す出力のみを表す（transcriptionText は含まない）
data class SummaryResult(
    val title: String,        // Gemini が生成した件名（20文字以内）
    val summaryText: String   // Gemini が生成した要約本文
)
```

### インターフェース

```kotlin
// text = Groq の読みおこし結合テキスト
fun interface SummaryProvider {
    suspend fun summarize(text: String): Result<SummaryResult>
}
```

---

### Gemini 構造化出力（Schema指定）★採用

`responseMimeType = "application/json"` に加え `responseSchema` でキーと型を API 側に強制させる。  
Gemini の出力は `title` と `summaryText` の **2フィールドのみ**。`transcriptionText` は Groq 側で生成済みのため不要。

```kotlin
val generativeModel = GenerativeModel(
    modelName = "gemini-1.5-flash",
    apiKey = BuildConfig.GEMINI_API_KEY,
    generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = Schema(
            name = "SummaryResult",
            description = "音声の要約結果",
            type = FunctionDeclaration.Type.OBJECT,
            properties = mapOf(
                "title" to Schema(
                    type = Type.STRING,
                    description = "20文字以内の簡潔なタイトル"
                ),
                "summaryText" to Schema(
                    type = Type.STRING,
                    description = "要約本文"
                )
            ),
            required = listOf("title", "summaryText")
        )
    }
)
```

### プロンプト（Groqの読みおこし結合テキストを渡すだけ）

```kotlin
// combinedTranscription = ChunkEntity.transcriptionText をチャンク順に結合した文字列
val response = generativeModel.generateContent(combinedTranscription)

// スキーマ通りの JSON が保証されるため、即座にデシリアライズ可能
val summaryResult = Json.decodeFromString<SummaryResult>(response.text!!)
```

### SummarizeUseCase の戻り値（案A採用）

`SummaryResult`（Gemini出力）だけでは `transcriptionText` を呼び出し元に渡せないため、
両方をまとめた `SummarizeOutput` を戻り値にする。

```kotlin
// SummarizeOutput — UseCase の戻り値
data class SummarizeOutput(
    val summaryResult: SummaryResult,       // Gemini 生成（title + summaryText）
    val transcriptionText: String           // Groq 結合テキスト
)
```

```kotlin
suspend fun execute(sessionId: String): Result<SummarizeOutput> {
    val chunks = chunkRepository.getBySession(sessionId)

    // 1. Groq の読みおこしテキストを結合
    //    DONE チャンク: テキストをそのまま使用
    //    FAILED チャンク: "[音声認識エラー]" プレースホルダーを挿入（欠落を Gemini に明示）
    val combinedTranscription = chunks
        .sortedBy { it.chunkIndex }
        .joinToString("\n\n") { chunk ->
            when (chunk.status) {
                ChunkStatus.DONE -> chunk.transcriptionText ?: ""
                ChunkStatus.FAILED -> "[音声認識エラー]"
                else -> ""
            }
        }
        .trim()

    // 2. Gemini で title + summaryText を生成
    val summaryResult = summaryProvider.summarize(combinedTranscription)
        .getOrElse { return Result.failure(it) }

    // 3. 両方をまとめて返す → 呼び出し元が SummaryEntity に保存
    return Result.success(SummarizeOutput(summaryResult, combinedTranscription))
}
```

### パース失敗時のフォールバック（念のため残す）

稀な SDK エラー等に備えてフォールバックを `SummaryRepository` に実装する:

- `title` → `"yyyy/MM/dd HH:mm 録音"` （日時ベースで自動生成）
- `summaryText` → Gemini の生レスポンスをそのまま使用

---

## DBスキーマ

### 新規テーブル: `summaries`

```kotlin
@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val sessionId: String,       // 録音開始時に UUID.randomUUID() で生成
    val createdAt: Long,
    val title: String,
    val summaryText: String,
    val transcriptionText: String,           // AI生成の読みおこし全文
    val audioFilePath: String,               // context.filesDir/recordings/{sessionId}.wav
    val durationMs: Long,
    val status: SummaryStatus,               // RECORDED / SUMMARIZING / DONE / ERROR
    val isRead: Boolean = false,
    val errorMessage: String? = null
)

enum class SummaryStatus { RECORDED, SUMMARIZING, DONE, ERROR }
```

### ファイル保存規則

| 種別 | パス |
|------|------|
| WAV結合ファイル | `context.filesDir/recordings/{sessionId}.wav` |
| チャンクファイル | `context.filesDir/recordings/{sessionId}/chunk_*.wav` |

チャンクファイルは `WavMerger.merge()` 成功後に全削除する。

### 録音停止〜要約完了のフロー

```
録音停止
  └─ sessionId (録音開始時に生成済み UUID) を使用
  └─ WavMerger.merge(chunkFiles, outputFile) → durationMs   ← withContext(Dispatchers.IO)
       └─ chunkFiles.forEach { it.delete() }    ← チャンクWAV削除（必須）
  └─ ChunkDao.deleteBySession(sessionId)         ← DBのチャンクレコード削除（㈠2肝大化防止）
  └─ SummaryDao.insert(status=RECORDED, audioFilePath, durationMs)  ← 先行insert
  └─ SummaryDao.updateStatus(SUMMARIZING)
  └─ SummarizeUseCase.execute(sessionId) → SummarizeOutput
       ├─ 成功: SummaryDao.updateStatus(DONE, title, summaryText, transcriptionText)
       └─ 失敗: SummaryDao.updateStatus(ERROR, errorMessage)
```

### アプリ再起動時の再試行

**実行タイミング**: `MainViewModel.init` で即座に実行するのではなく、`RecordingController` の状態同期完了（Serviceバインド完了または `isReady` Flow の発火）を待ってから実行する。起動直後に Service との状態同期が完了していない場合、`currentSessionId` が `null` を返して録音中ファイルを誤削除するリスクがあるため。

```kotlin
init {
    viewModelScope.launch {
        // Serviceバインド・状態同期完了を待つ（レースコンディション防止）
        recordingController.awaitReady()  // Flow/suspend で実装
        val activeSessionId = recordingController.currentSessionId

        // 未完了レコードを取得して再試行
        summaryDao.getByStatus(listOf(RECORDED, SUMMARIZING)).forEach { entity ->
            summaryDao.updateStatus(entity.sessionId, SUMMARIZING)
            summarizeUseCase.execute(entity.sessionId)
                .onSuccess { output ->
                    summaryDao.updateStatus(
                        entity.sessionId, DONE,
                        output.summaryResult.title,
                        output.summaryResult.summaryText,
                        output.transcriptionText
                    )
                }
                .onFailure { summaryDao.updateStatus(entity.sessionId, ERROR, it.message) }
        }
        // 孤児ファイルのクリーンアップ（アクティブセッションは除外）
        cleanupOrphanFiles(excludeSessionId = activeSessionId)
    }
}
```

### 孤児ファイルのクリーンアップ

`filesDir/recordings/` 配下に存在するが `summaries` DBに登録されていないWAVファイル・チャンクファイルを削除する。  
**必ず `excludeSessionId`（現在録音中の sessionId）を除外すること**。  
**実行タイミング**: `RecordingController.awaitReady()` 完了後に実行し、状態同期前の誤削除を防止する。

### DBバージョン

- `AppDatabase` version `1 → 2`
- `Migration(1, 2)`: `CREATE TABLE summaries ...` を追加

---

## 新規: WAVファイル結合（`WavMerger`）

```kotlin
object WavMerger {
    /**
     * 複数のWAVチャンクファイルを1つのWAVファイルに結合する。
     *
     * - チャンクが1ファイルの場合はコピーのみ（ショートサーキット）。
     * - 各チャンクのWAVヘッダー（44バイト）をスキップし、PCMデータのみを結合する。
     * - 全体のデータサイズを計算して新しいWAVヘッダーを先頭に書き込む。
     * - 結合成功後、chunkFiles の各ファイルを削除する。
     *
     * @return 再生時間（ミリ秒）
     */
    fun merge(chunkFiles: List<File>, outputFile: File): Long
}
```

---

## 一時停止機能

```kotlin
// GaplessRecorder（追加）
// isPaused フラグを追加。一時停止中は isRecording=true を維持し、チャンク分割は動作させない。
suspend fun pause()    // isPaused = true、AudioRecord停止
suspend fun resume()   // isPaused = false、AudioRecord再開

// RecordingController（追加）
fun pauseRecording()
fun resumeRecording()

// RecordingService（追加）
// ACTION_PAUSE: 通知テキストを「一時停止中」に更新し、アクションを「再開」(ACTION_RESUME) に差し替え
// ACTION_RESUME: 通知テキストを「録音中」に戻し、アクションを「一時停止」(ACTION_PAUSE) に差し替え
ACTION_PAUSE / ACTION_RESUME

// UiState（追加）
val isPaused: Boolean = false
val recordingSeconds: Int = 0    // タイマー表示用（一時停止中は加算停止）
```

---

## 新規ユースケース: `DeleteSummaryUseCase`

```kotlin
class DeleteSummaryUseCase @Inject constructor(
    private val summaryDao: SummaryDao
) {
    suspend fun execute(sessionId: String, audioFilePath: String) {
        // 1. 再生中の場合は Player.stop() / release() を先に呼ぶ（呼び出し元の責務）
        // 2. WAVファイル削除
        File(audioFilePath).delete()
        // 3. DBレコード削除
        summaryDao.delete(sessionId)
    }
}
```

---

## 変更ファイル一覧

### 新規作成

| ファイル | 内容 |
|---------|------|
| `SummaryResult.kt` | `data class SummaryResult(title, summaryText, transcriptionText)` |
| `SummaryStatus.kt` | `enum class SummaryStatus { RECORDED, SUMMARIZING, DONE, ERROR }` |
| `SummaryEntity.kt` | summariesテーブルエンティティ |
| `SummaryDao.kt` | insert / getAll / getByStatus / updateStatus / updateTitle / updateRead / delete |
| `SummaryPersistenceRepository.kt` | DAO wrapper（interface + impl） |
| `WavMerger.kt` | チャンクWAV結合ユーティリティ |
| `DeleteSummaryUseCase.kt` | WAVファイル + DBレコード一括削除 |

### 変更

| ファイル | 主な変更 |
|---------|---------|
| `SummaryProvider.kt` | 戻り値を `Result<SummaryResult>` に変更 |
| `SummaryRepository.kt` | JSONモード・レスポンスデシリアライズ・フォールバック・戻り値変更 |
| `MockSummaryProvider.kt` | 戻り値を `SummaryResult` に変更 |
| `SummarizeUseCase.kt` | 戻り値を `Result<SummaryResult>` に変更 |
| `AppDatabase.kt` | version 2、SummaryEntity追加、Migration(1,2)追加 |
| `GaplessRecorder.kt` | isPaused フラグ・pause / resume 追加 |
| `RecordingController.kt` | pause / resume 追加 |
| `RecordingManager.kt` | stopRecording に WavMerger・先行insert処理追加 |
| `RecordingService.kt` | ACTION_PAUSE / RESUME・通知更新処理追加 |
| `ServiceRecordingController.kt` | pause / resume 実装 |
| `MainViewModel.kt` | isPaused・recordingSeconds・status遷移・バッジカウント・isRead・再起動時再試行・孤児クリーンアップ |
| `MainActivity.kt` | 3タブUI全面リデザイン（NavigationBar・バッジ・EmptyState・詳細画面） |
| `RepositoryModule.kt` | SummaryPersistenceRepository バインディング追加 |
| `DataStoreModule.kt` | **[NEW]** Hilt `@Module`。`DataStore<Preferences>` を Singleton で Provide |
| `build.gradle` | ExoPlayer（Media3）依存・DataStore依存追加 |

---

## 実装順序

```
Phase 1: SummaryResult（title, summaryText のみ）/ SummarizeOutput（SummaryResult + transcriptionText）
         / SummaryProvider / SummarizeUseCase の戻り値を Result<SummarizeOutput> に変更
         └ Gemini 構造化出力（Schema）・パースフォールバック処理を含む

Phase 2: SummaryEntity（status / isRead / errorMessage 含む）
         / SummaryDao（getByStatus 追加）/ AppDatabase Migration(1→2)

Phase 3: DeleteSummaryUseCase 新規追加

Phase 4: WavMerger 実装
         └ PCMのみ結合・ヘッダー再構築
         └ 1ファイルショートサーキット
         └ チャンク削除処理を含む

Phase 5: GaplessRecorder 一時停止機能

Phase 6: RecordingController / RecordingManager（先行insert・WavMerger呼び出し）
         / RecordingService（通知更新・Pause/Resume切替）

Phase 7: MainViewModel
         （isPaused・recordingSeconds・status遷移・バッジカウント・isRead
           再起動時再試行ロジック・孤児ファイルクリーンアップ）

Phase 8: UI
         RecordingTab（タイマー点滅・音量バー状態制御）
         → AudioTab（循環トグル速度ボタン・Slider・再生完了リセット・
                     ライフサイクル再生停止・削除時Player解放・EmptyState）
         → SummaryTab（ローディング・ERROR再試行フロー・EmptyState・
                       バッジ・isRead既読処理・詳細画面）
         → NavigationBar（バッジ 99+ 上限表示）

Phase 9: テスト更新
         └ GaplessRecorder: pause → resume → stop
         └ WavMerger: ヘッダーバイト数検証・1ファイルショートサーキット
         └ SummaryDao: CRUD全操作・status遷移・getByStatus
         └ SummarizeUseCase: SummaryResult の title/summaryText/transcriptionText 検証
                             ・不正フォーマット返却時のフォールバック検証
         └ MainViewModel: isPaused・recordingSeconds・バッジカウント・再試行ロジック
         └ DeleteSummaryUseCase: WAVファイル削除 + DBレコード削除の連動
         └ AppDatabaseMigrationTest: MigrationTestHelper で version 1→2 を検証
```

---

## 検証計画

### 自動テスト

| テスト | 検証内容 |
|--------|---------|
| `GaplessRecorderPauseTest` | pause → resume → stop で正常ファイル生成 |
| `WavMergerTest` | 結合WAVのヘッダーバイト数が正確か・1ファイルショートサーキット動作 |
| `SummaryDaoTest` | insert / getByStatus / updateStatus / updateTitle / updateRead / delete |
| `SummarizeUseCaseTest` | title/summaryText/transcriptionText が揃って返るか・不正フォーマット時フォールバック |
| `MainViewModelTest` | isPaused・recordingSeconds・バッジカウント・再試行ロジック |
| `DeleteSummaryUseCaseTest` | WAVファイル削除 + DBレコード削除の連動 |
| `AppDatabaseMigrationTest` | `MigrationTestHelper` で version 1→2 スキーマ適用を検証 |

### 手動検証（実機）

- 録音 → 一時停止 → 再開 → 停止 → タブ2で再生できるか
- 一時停止中に通知テキスト・ボタンが切り替わるか
- 再生速度が起動後も保持されるか
- タブ3にAI生成タイトル・要約・読みおこしが表示されるか（RECORDED→DONE のローディング遷移含む）
- タイトル編集 → 保存後に一覧が更新されるか
- 詳細画面を開いたらバッジが減るか（99+ 上限も確認）
- タスクキル後の再起動で RECORDED レコードが再試行されるか
- タブ2・タブ3それぞれの削除でWAVファイルとDBレコードが両方消えるか
- 再生中アイテムを削除してもクラッシュしないか
- 再生完了後に位置が0にリセットされるか
- バックグラウンド移行で再生が一時停止されるか
- バックグラウンド録音中にアプリを再起動しても録音中WAVが削除されないか

---

## 実装 Tips

### WavMerger のスレッド指定

ファイル IO バウンドの処理のため、呼び出し時は必ず `Dispatchers.IO` で包む（ANR 防止）:

```kotlin
// RecordingManager 内
val durationMs = withContext(Dispatchers.IO) {
    WavMerger.merge(chunkFiles, outputFile)
}
```

### Gemini systemInstruction（出力精度向上）

`responseSchema` に加えて `systemInstruction` を設定すると出力が安定する:

```kotlin
val generativeModel = GenerativeModel(
    modelName = "gemini-1.5-flash",
    apiKey = BuildConfig.GEMINI_API_KEY,
    systemInstruction = content {
        text("あなたは優秀な要約アシスタントです。提供された音声の読みおこしテキストから、20文字以内のタイトルと詳細な要約を生成してください。")
    },
    generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = /* ... */
    }
)
```

### 再生速度の DataStore 読み込み

`DataStore<Preferences>` は非同期（Flow）のため、ViewModel で `stateIn` して UI に反映する:

```kotlin
// ViewModel 内
val playbackSpeed: StateFlow<Float> = dataStore.data
    .map { it[PLAYBACK_SPEED_KEY] ?: 1.0f }
    .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
```

UI 側は `playbackSpeed.collectAsStateWithLifecycle()` で初期値から一貫した表示を実現する。
