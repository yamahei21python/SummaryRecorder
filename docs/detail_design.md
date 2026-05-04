# 詳細設計書：SummaryRecorder

---

## 1. クラス一覧と依存関係

### 1.1 クラス一覧

| クラス | レイヤ | 責務 | ファイル |
|---|---|---|---|
| `ChunkEntity` | Data | チャンクDBレコード | `data/db/ChunkEntity.kt` |
| `ChunkStatus` | Data | チャンク状態enum | `data/db/ChunkEntity.kt` |
| `ChunkDao` | Data | DBアクセスオブジェクト | `data/db/ChunkDao.kt` |
| `AppDatabase` | Data | Room DB定義 | `data/db/AppDatabase.kt` |
| `GroqApiService` | Data | Groq HTTP APIインタフェース | `data/api/GroqApiService.kt` |
| `GroqTranscriptionResponse` | Data | Groq レスポンスDTO | `data/api/GroqApiService.kt` |
| `TranscriptionRepository` | Data | 文字起こしorchestration | `data/repository/TranscriptionRepository.kt` |
| `SummaryRepository` | Data | Gemini要約呼出 | `data/repository/SummaryRepository.kt` |
| `WavHeaderWriter` | Recorder | WAVヘッダー生成・上書き | `recorder/WavHeaderWriter.kt` |
| `GaplessRecorder` | Recorder | ギャップレス連続録音 | `recorder/GaplessRecorder.kt` |
| `RecordingService` | Service | Foreground Service本体 | `service/RecordingService.kt` |
| `RetryWorker` | Service | WorkManager定期再送 | `service/RetryWorker.kt` |
| `MainViewModel` | Presentation | UI状態管理・要約Flow | `viewmodel/MainViewModel.kt` |
| `MainActivity` | Presentation | Compose UI単一画面 | `ui/MainActivity.kt` |
| `SummaryRecorderApp` | DI | Application + DIコンテナ | `SummaryRecorderApp.kt` |
| `ServiceLocator` | DI | 手動DIコンテナ | `di/ServiceLocator.kt` |

### 1.2 依存関係図

```
MainActivity
  └→ MainViewModel
       ├→ ChunkDao (Flow観測)
       ├→ SummaryRepository
       │    └→ GenerativeModel (Gemini SDK)
       └→ TranscriptionRepository
            └→ GroqApiService (Retrofit)

RecordingService
  ├→ GaplessRecorder
  │    └→ WavHeaderWriter
  ├→ ChunkDao
  └→ TranscriptionRepository

RetryWorker
  ├→ ChunkDao
  └→ TranscriptionRepository
```

---

## 2. Data層 詳細設計

### 2.1 ChunkEntity + ChunkStatus

```kotlin
// data/db/ChunkEntity.kt

@Entity(tableName = "chunks", indices = [
    Index(value = ["sessionId"]),
    Index(value = ["status"])
])
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "status")
    val status: ChunkStatus,

    @ColumnInfo(name = "transcription_text")
    val transcriptionText: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ChunkStatus {
    PENDING,
    UPLOADING,
    DONE,
    FAILED
}
```

**設計判断**:
- `sessionId` と `status` にIndex付与（クエリ頻度高）
- `transcriptionText` はnull許容（PENDING/FAILED時は未設定）
- TypeConverter不要（Room enum自動マッピング）

### 2.2 ChunkDao

```kotlin
// data/db/ChunkDao.kt

@Dao
interface ChunkDao {

    // ===== CREATE =====
    @Insert
    suspend fun insert(chunk: ChunkEntity): Long

    // ===== READ =====
    @Query("SELECT * FROM chunks WHERE id = :id")
    suspend fun getById(id: Long): ChunkEntity?

    @Query("SELECT * FROM chunks WHERE session_id = :sessionId ORDER BY chunk_index ASC")
    suspend fun getBySession(sessionId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE status = :status")
    suspend fun getByStatus(status: ChunkStatus): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE session_id = :sessionId ORDER BY chunk_index ASC")
    fun observeBySession(sessionId: String): Flow<List<ChunkEntity>>

    @Query("SELECT COUNT(*) FROM chunks WHERE session_id = :sessionId AND status = :status")
    suspend fun countByStatus(sessionId: String, status: ChunkStatus): Int

    // ===== UPDATE =====
    @Query("""
        UPDATE chunks
        SET status = :newStatus,
            transcription_text = :text,
            updated_at = :now
        WHERE id = :id
    """)
    suspend fun updateStatus(
        id: Long,
        newStatus: ChunkStatus,
        text: String? = null,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE chunks
        SET status = 'FAILED', updated_at = :now
        WHERE status = 'UPLOADING'
    """)
    suspend fun resetStuckUploads(now: Long = System.currentTimeMillis())

    // ===== DELETE =====
    @Query("DELETE FROM chunks WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM chunks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
```

**設計判断**:
- `updateStatus` は1メソッドで状態＋テキストを同時更新（atomic性）
- `observeBySession` のみFlow返却（リアクティブ監視用）
- `countByStatus` で全DONE判定の高速化（全取得せずCOUNT）

### 2.3 AppDatabase

```kotlin
// data/db/AppDatabase.kt

@Database(
    entities = [ChunkEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "summary_recorder.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
```

- シングルトン（`@Volatile` + `synchronized` + DCL）
- `exportSchema = false`（バージョン1固定・個人利用）

### 2.4 GroqApiService

```kotlin
// data/api/GroqApiService.kt

data class GroqTranscriptionResponse(
    val text: String
)

interface GroqApiService {

    @Multipart
    @POST("openai/v1/audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody,
        @Part("response_format") responseFormat: RequestBody
    ): GroqTranscriptionResponse
}
```

**設計判断**:
- `Authorization` ヘッダーは各リクエストで注入（ServiceLocator経由）
- `model`: `"whisper-large-v3"`, `language`: `"ja"`, `response_format`: `"json"` 固定
- ファイルは `RequestBody` でストリーム化（後述）

### 2.5 TranscriptionRepository

```kotlin
// data/repository/TranscriptionRepository.kt

class TranscriptionRepository(
    private val apiService: GroqApiService,
    private val apiKey: String
) {
    suspend fun transcribe(file: File): Result<String> {
        return runCatching {
            val fileBody = file.asRequestBody(
                "audio/wav".toMediaType()
            )
            val filePart = MultipartBody.Part.createFormData(
                "file", file.name, fileBody
            )

            val response = apiService.transcribe(
                authorization = "Bearer $apiKey",
                file = filePart,
                model = "whisper-large-v3".toRequestBody("text/plain".toMediaType()),
                language = "ja".toRequestBody("text/plain".toMediaType()),
                responseFormat = "json".toRequestBody("text/plain".toMediaType())
            )

            response.text
        }
    }
}
```

**設計判断**:
- `Result<String>` で成功/失敗を包む（呼出元でハンドリング）
- `file.asRequestBody()` → OkHttp内で自動ストリーム読込（OOM回避）
- 例外はRetrofitが自動変換（HTTP 401/429 → 例外スロー）

### 2.6 SummaryRepository

```kotlin
// data/repository/SummaryRepository.kt

class SummaryRepository(
    private val generativeModel: GenerativeModel
) {
    companion object {
        private const val SYSTEM_PROMPT = """
あなたは議事録作成の専門家です。
以下の文字起こしテキストを要約してください。
出力形式:
1. 概要（3行以内）
2. 主要トピック（箇条書き）
3. 重要な発言・決定事項
4. アクションアイテム（あれば）
"""
        private const val TIMEOUT_MS = 60_000L // 60秒（長文要約用）
    }

    suspend fun summarize(combinedText: String): Result<String> {
        return runCatching {
            withTimeout(TIMEOUT_MS) {
                val response = generativeModel.generateContent(
                    content { text(combinedText) }
                )
                response.text
                    ?: throw IllegalStateException("Gemini returned empty response")
            }
        }
    }
}
```

**設計判断**:
- `SYSTEM_PROMPT` で出力形式を規定（要約品質安定化）
- `generateContent` は同期呼出（suspend内で安全）
- 空レスポンス → `IllegalStateException` で明示的エラー

---

## 3. Recorder層 詳細設計

### 3.1 WavHeaderWriter

```kotlin
// recorder/WavHeaderWriter.kt

object WavHeaderWriter {

    private const val HEADER_SIZE = 44

    /**
     * WAVヘッダーを書込む。
     * 呼出前にファイルポインタは先頭にあること。
     * 書込後、ポインタはHEADER_SIZEの位置にある。
     */
    fun writeHeader(
        file: RandomAccessFile,
        dataLength: Long,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val fileSize = HEADER_SIZE + dataLength

        file.seek(0)

        // RIFF header
        file.writeBytes("RIFF")
        file.writeIntLE(fileSize - 8)       // チャンクサイズ
        file.writeBytes("WAVE")

        // fmt sub-chunk
        file.writeBytes("fmt ")
        file.writeIntLE(16)                 // fmtチャンクサイズ（固定）
        file.writeShortLE(1)                // PCM = 1
        file.writeShortLE(channels.toShort())
        file.writeIntLE(sampleRate)
        file.writeIntLE(byteRate)
        file.writeShortLE(blockAlign.toShort())
        file.writeShortLE(bitsPerSample.toShort())

        // data sub-chunk
        file.writeBytes("data")
        file.writeIntLE(dataLength.toInt())
    }

    /**
     * ダミーヘッダー（dataLength=0）を書込む。
     * 録音開始時に使用。終了時に seek(0) で上書き。
     */
    fun writeDummyHeader(file: RandomAccessFile) {
        writeHeader(file, dataLength = 0)
    }

    // ---- Little-Endian helper ----
    private fun RandomAccessFile.writeIntLE(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value shr 8) and 0xFF)
        writeByte((value shr 16) and 0xFF)
        writeByte((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Short) {
        writeByte(value.toInt() and 0xFF)
        writeByte((value.toInt() shr 8) and 0xFF)
    }

    private fun RandomAccessFile.writeBytes(s: String) {
        s.toByteArray(Charsets.US_ASCII).forEach { writeByte(it.toInt()) }
    }
}
```

**設計判断**:
- `object`（シングルトン）— ステートレスなのでインスタンス不要
- Little-Endian手動書込（`RandomAccessFile` はBE-onlyのため）
- `writeDummyHeader` は録音開始時の専用ショートカット

### 3.2 GaplessRecorder

```kotlin
// recorder/GaplessRecorder.kt

class GaplessRecorder(
    private val outputDir: File,
    private val chunkSizeBytes: Long = 19L * 1024 * 1024, // 19MB
    private val onChunkComplete: (chunkIndex: Int, file: File) -> Unit
) {
    // AudioConfig
    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val mutex = Mutex()
    private var audioRecord: AudioRecord? = null
    private var currentFile: RandomAccessFile? = null
    private var currentChunkIndex = 0
    private var currentBytesWritten = 0L
    private var isRecording = false
    private val recordingScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )

    // ===== 公開API =====

    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        require(minBufferSize != AudioRecord.ERROR_BAD_VALUE) {
            "AudioRecord: unsupported audio config"
        }
        // デバイスによっては最小値が小さすぎてオーバーランする可能性あり
        val bufferSize = maxOf(minBufferSize, 4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        ).also { it.startRecording() }

        isRecording = true
        currentChunkIndex = 0
        currentBytesWritten = 0L

        // 単一コルーチン: 初期化→録音ループ（順序保証・レースなし）
        recordingScope.launch {
            mutex.withLock { openNewFile() } // 最初に必ず実行

            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: break
                if (read <= 0) continue

                mutex.withLock {
                    if (!isRecording) return@withLock
                    writePcmData(buffer, read)

                    if (currentBytesWritten >= chunkSizeBytes) {
                        finalizeCurrentChunk()
                        currentChunkIndex++
                        currentBytesWritten = 0L
                        openNewFile()
                    }
                }
            }
        }
    }

    /**
     * 停止フロー:
     * 1. isRecording=false → ループ脱出フラグ
     * 2. audioRecord.release() → read()が即座に終了（ループ確実終了）
     * 3. cancelChildren() → 残存コルーチン破棄
     * 4. mutex.withLock { finalizeCurrentChunk() } → 最終チャンク安全確定
     */
    fun stop() {
        isRecording = false

        // audioRecordを先にrelease → read()が即座に返る → ループ終了
        audioRecord?.apply {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        audioRecord = null

        // コルーチンキャンセル（release済みなのでreadは抜けている）
        recordingScope.coroutineContext.cancelChildren()

        // Mutexで安全に最終チャンク確定
        runBlocking {
            mutex.withLock {
                finalizeCurrentChunk()
            }
        }
    }

    // ===== 内部メソッド =====

    private fun openNewFile() {
        val file = File(outputDir, "chunk_${currentChunkIndex}.wav")
        currentFile = RandomAccessFile(file, "rw").also {
            WavHeaderWriter.writeDummyHeader(it)
        }
    }

    private fun writePcmData(buffer: ShortArray, readCount: Int) {
        // Short → Byte 変換（Little-Endian）
        val byteBuffer = ByteBuffer.allocate(readCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asShortBuffer().put(buffer, 0, readCount)
        currentFile!!.write(byteBuffer.array())
        currentBytesWritten += readCount * 2L
    }

    private fun finalizeCurrentChunk() {
        currentFile?.let { raf ->
            val dataLength = currentBytesWritten
            WavHeaderWriter.writeHeader(raf, dataLength)
            raf.close()
            currentFile = null

            val file = File(outputDir, "chunk_${currentChunkIndex}.wav")
            if (file.exists() && dataLength > 0) {
                onChunkComplete(currentChunkIndex, file)
            }
        }
    }
}
```

**設計判断**:
- `ShortArray` → `ByteBuffer` でLE変換（PCM 16bit仕様準拠）
- `CoroutineScope(Dispatchers.IO)` で録音ループ独立動作
- `stop()` で `cancelChildren()` → ループ即停止 → Mutex内で最終チャンク確定
- `onChunkComplete` はファイルclose後に呼出（ファイルアクセス安全性）
- チャンクファイル名は `chunk_{index}.wav`（シーケンシャル）

---

## 4. Service層 詳細設計

### 4.1 RecordingService

```kotlin
// service/RecordingService.kt

class RecordingService : Service() {

    private companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_SESSION_ID = "session_id"
    }

    // --- DI（ServiceLocator経由） ---
    private val dao: ChunkDao by lazy {
        ServiceLocator.database.chunkDao()
    }
    private val transcriptionRepo: TranscriptionRepository by lazy {
        ServiceLocator.transcriptionRepository
    }
    private val summaryRepo: SummaryRepository by lazy {
        ServiceLocator.summaryRepository
    }

    private var recorder: GaplessRecorder? = null
    private var sessionId: String = ""
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ===== ライフサイクル =====

    override fun onCreate() {
        super.onCreate()
        // 1. 自己修復（runBlockingで同期待ち → onStartCommandの録音開始前に確実に完了）
        runBlocking { dao.resetStuckUploads() }
        // 2. 通知チャンネル
        createNotificationChannel()
        // 3. バッテリー最適化確認
        requestBatteryOptimization()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                    ?: UUID.randomUUID().toString()

                // Foreground開始
                startForeground(NOTIFICATION_ID, buildNotification("録音中..."))

                // WorkManager登録
                scheduleRetryWorker()

                // 録音開始
                startRecording()
            }
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recorder?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ===== 録音制御 =====

    private fun startRecording() {
        val outputDir = File(filesDir, "recordings/$sessionId").also {
            it.mkdirs()
        }

        recorder = GaplessRecorder(
            outputDir = outputDir,
            onChunkComplete = { chunkIndex, file ->
                serviceScope.launch {
                    onChunkRecorded(chunkIndex, file)
                }
            }
        ).also { it.start() }
    }

    private fun stopRecording() {
        recorder?.stop()
        recorder = null
        updateNotification("文字起こし処理中...")
        // 全完了監視はMainViewModel側のFlowで行う
    }

    // ===== チャンク処理 =====

    private suspend fun onChunkRecorded(chunkIndex: Int, file: File) {
        // 1. DB登録
        val entity = ChunkEntity(
            sessionId = sessionId,
            chunkIndex = chunkIndex,
            filePath = file.absolutePath,
            status = ChunkStatus.PENDING
        )
        val id = dao.insert(entity)

        // 2. 即時アップロード
        uploadChunk(entity.copy(id = id))
    }

    private suspend fun uploadChunk(chunk: ChunkEntity) {
        // UPLOADINGに遷移
        dao.updateStatus(chunk.id, ChunkStatus.UPLOADING)

        val file = File(chunk.filePath)
        val result = transcriptionRepo.transcribe(file)

        if (result.isSuccess) {
            val text = result.getOrThrow()
            // DONE + テキスト保存
            dao.updateStatus(chunk.id, ChunkStatus.DONE, text)
            // 音声ファイル削除（NFR-005）
            file.delete()
        } else {
            // FAILED
            dao.updateStatus(chunk.id, ChunkStatus.FAILED)
        }
    }

    // ===== 通知 =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "録音サービス",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SummaryRecorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ===== バッテリー最適化 =====

    private fun requestBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    // ===== WorkManager =====

    private fun scheduleRetryWorker() {
        val request = PeriodicWorkRequestBuilder<RetryWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "retry_transcription",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    // ===== Companion (Intent Builder) =====

    companion object {
        fun startIntent(context: Context, sessionId: String): Intent {
            return Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
```

**設計判断**:
- `START_STICKY` でシステム強制終了後も再生成
- `onChunkComplete` コールバック内で即時アップロード開始（録音と並行）
- 音声ファイル削除はDONE確認後即時（NFR-005）
- Intent Builder でActivityからの呼出を型安全に

### 4.2 RetryWorker

```kotlin
// service/RetryWorker.kt

class RetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val dao: ChunkDao by lazy {
        ServiceLocator.database.chunkDao()
    }
    private val transcriptionRepo: TranscriptionRepository by lazy {
        ServiceLocator.transcriptionRepository
    }

    override suspend fun doWork(): Result {
        val failedChunks = dao.getByStatus(ChunkStatus.FAILED)

        // セッション単位でグループ化（ファイル消失時の整合性チェック用）
        val bySession = failedChunks.groupBy { it.sessionId }

        bySession.forEach { (sessionId, chunks) ->
            chunks.forEach { chunk ->
                val file = File(chunk.filePath)
                if (!file.exists()) {
                    // ファイル消失 → 再送不可 → セッション内の残りFAILEDも全削除
                    // 理由: 一部チャンク消失 → 全DONE到達不可能 → ゾンビレコード防止
                    dao.deleteBySession(sessionId)
                    return@forEach
                }

                dao.updateStatus(chunk.id, ChunkStatus.UPLOADING)

                val result = transcriptionRepo.transcribe(file)
                if (result.isSuccess) {
                    dao.updateStatus(chunk.id, ChunkStatus.DONE, result.getOrThrow())
                    file.delete()
                } else {
                    dao.updateStatus(chunk.id, ChunkStatus.FAILED)
                }
            }
        }

        return Result.success()
    }
}
```

**設計判断**:
- ファイル消失時はセッション単位で全削除（一部消失 → 全DONE到達不可能 → ゾンビレコード防止）
- 1チャンクずつ順次処理（並行でOOMリスク回避）
- 成否に関わらず `Result.success()`（WorkManagerのリトライは指数バックオフに任せない方針）

---

## 5. Presentation層 詳細設計

### 5.1 MainViewModel

```kotlin
// viewmodel/MainViewModel.kt

class MainViewModel(
    private val dao: ChunkDao,
    private val summaryRepo: SummaryRepository
) : ViewModel() {

    // ===== UI State =====

    data class UiState(
        val isRecording: Boolean = false,
        val sessionId: String = "",
        val chunks: List<ChunkUiItem> = emptyList(),
        val summary: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    data class ChunkUiItem(
        val index: Int,
        val status: ChunkStatus,
        val transcription: String?
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ===== 録音制御 =====

    fun startRecording(context: Context) {
        val sessionId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                isRecording = true,
                sessionId = sessionId,
                summary = null,
                error = null
            )
        }
        context.startService(
            RecordingService.startIntent(context, sessionId)
        )
        observeChunks(sessionId)
    }

    fun stopRecording(context: Context) {
        _uiState.update { it.copy(isRecording = false, isLoading = true) }
        context.startService(RecordingService.stopIntent(context))
    }

    // ===== チャンク監視 =====

    private fun observeChunks(sessionId: String) {
        viewModelScope.launch {
            dao.observeBySession(sessionId)
                .map { chunks ->
                    chunks.map { entity ->
                        ChunkUiItem(
                            index = entity.chunkIndex,
                            status = entity.status,
                            transcription = entity.transcriptionText
                        )
                    }
                }
                .onEach { items ->
                    _uiState.update { it.copy(chunks = items) }
                }
                .collect()
        }

        // 全DONE検知 → 要約トリガー
        viewModelScope.launch {
            dao.observeBySession(sessionId)
                .map { chunks ->
                    chunks.isNotEmpty() && chunks.all { it.status == ChunkStatus.DONE }
                }
                .distinctUntilChanged()
                .filter { it }
                .onEach {
                    summarizeAll(sessionId)
                }
                .collect()
        }
    }

    // ===== 要約 =====

    private suspend fun summarizeAll(sessionId: String) {
        val chunks = dao.getBySession(sessionId)
        val combinedText = chunks
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n") { it.transcriptionText ?: "" }

        val result = summaryRepo.summarize(combinedText)

        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    summary = result.getOrThrow(),
                    isLoading = false
                )
            }
            // DB物理削除（SUM-004）
            dao.deleteBySession(sessionId)
        } else {
            _uiState.update {
                it.copy(
                    error = "要約に失敗しました: ${result.exceptionOrNull()?.message}",
                    isLoading = false
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
```

**設計判断**:
- `UiState` 単一StateFlow（Compose推奨パターン）
- チャンク監視と全DONE検知は別Coroutine（独立ライフサイクル）
- `chunks.isNotEmpty()` ガードで空リスト誤検知防止
- 要約失敗時はエラー表示のみ（リトライはユーザー操作想定）

### 5.2 MainActivity（Compose UI）

```kotlin
// ui/MainActivity.kt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 権限チェック
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        }

        val viewModel: MainViewModel by viewModels {
            // ServiceLocator経由でDI
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(
                        dao = ServiceLocator.database.chunkDao(),
                        summaryRepo = ServiceLocator.summaryRepository
                    ) as T
                }
            }
        }

        setContent {
            SummaryRecorderTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // タイトル
        Text(
            text = "SummaryRecorder",
            style = MaterialTheme.typography.headlineMedium
        )

        // 録音ボタン
        Button(
            onClick = {
                val context = LocalContext.current
                if (uiState.isRecording) {
                    viewModel.stopRecording(context)
                } else {
                    viewModel.startRecording(context)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isRecording)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (uiState.isRecording) "録音停止" else "録音開始")
        }

        // チャンク状態一覧
        if (uiState.chunks.isNotEmpty()) {
            Text("チャンク状態", style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(uiState.chunks) { chunk ->
                    ChunkRow(chunk)
                }
            }
        }

        // ローディング
        if (uiState.isLoading) {
            CircularProgressIndicator()
            Text("文字起こし完了待ち...")
        }

        // 要約結果
        uiState.summary?.let { summary ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("要約結果", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // エラー表示
        uiState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun ChunkRow(chunk: MainViewModel.ChunkUiItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("チャンク ${chunk.index}")
        StatusBadge(chunk.status)
    }
}

@Composable
fun StatusBadge(status: ChunkStatus) {
    val (text, color) = when (status) {
        ChunkStatus.PENDING -> "待機中" to Color.Gray
        ChunkStatus.UPLOADING -> "送信中" to Color(0xFF2196F3) // Blue
        ChunkStatus.DONE -> "完了" to Color(0xFF4CAF50)       // Green
        ChunkStatus.FAILED -> "失敗" to Color(0xFFF44336)     // Red
    }
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
```

**設計判断**:
- 単一画面（MainActivityのみ）— Navigation不要
- `collectAsStateWithLifecycle` でLifecycle安全なFlow収集
- チャンク状態は色分けバッジで直感的表示
- 権限リクエストは `onCreate` で最初に実行

---

## 6. DI（ServiceLocator）

```kotlin
// di/ServiceLocator.kt

object ServiceLocator {

    @Volatile
    private var _database: AppDatabase? = null
    @Volatile
    private var _groqApiKey: String? = null
    @Volatile
    private var _geminiApiKey: String? = null

    fun initialize(context: Context) {
        _database = AppDatabase.getInstance(context)
    }

    fun setApiKeys(groqKey: String, geminiKey: String) {
        _groqApiKey = groqKey
        _geminiApiKey = geminiKey
    }

    val database: AppDatabase
        get() = _database ?: error("ServiceLocator not initialized")

    val groqApiService: GroqApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApiService::class.java)
    }

    val transcriptionRepository: TranscriptionRepository by lazy {
        TranscriptionRepository(
            apiService = groqApiService,
            apiKey = _groqApiKey ?: error("Groq API key not set")
        )
    }

    val summaryRepository: SummaryRepository by lazy {
        val model = GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = _geminiApiKey ?: error("Gemini API key not set")
        )
        SummaryRepository(generativeModel = model)
    }
}
```

```kotlin
// SummaryRecorderApp.kt

class SummaryRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(this)

        // local.properties からAPIキー読込
        val groqKey = BuildConfig.GROQ_API_KEY
        val geminiKey = BuildConfig.GEMINI_API_KEY
        ServiceLocator.setApiKeys(groqKey, geminiKey)
    }
}
```

**設計判断**:
- Hilt/Koin 導入せず手動DI（個人利用・クラス数少ない）
- APIキーは `BuildConfig` 経由（`build.gradle.kts` で `local.properties` 読込）
- `lazy` で遅延初期化（不要なコンポーネントは生成しない）

---

## 7. シーケンス図

### 7.1 録音→文字起こし（正常系）

```
User          MainActivity    RecordingService   GaplessRecorder   ChunkDao   GroqApi
  │                │                │                  │              │          │
  │──Start録音──→│                │                  │              │          │
  │                │──startService→│                  │              │          │
  │                │                │──start()────────→│              │          │
  │                │                │                  │←─read loop──│          │
  │                │                │                  │              │          │
  │                │                │    onChunkComplete(0, file)     │          │
  │                │                │←─────────────────│              │          │
  │                │                │──insert(PENDING)──────────────→│          │
  │                │                │──updateStatus(UPLOADING)──────→│          │
  │                │                │──transcribe(file)────────────────────────→│
  │                │                │←──text────────────────────────────────────│
  │                │                │──updateStatus(DONE, text)─────→│          │
  │                │                │──file.delete()   │              │          │
  │                │                │                  │              │          │
  │                │                │    onChunkComplete(1, file)     │          │
  │                │                │←─────────────────│              │          │
  │                │                │   ... repeat ... │              │          │
```

### 7.2 全完了→要約

```
MainViewModel     ChunkDao        SummaryRepository   GeminiAPI
     │                │                  │                │
     │──observeBySession(sessionId)──→│   │                │
     │                │                  │                │
     │    Flow: [DONE, DONE, DONE]       │                │
     │←───────────────│                  │                │
     │                │                  │                │
     │  all { DONE } == true → trigger   │                │
     │──getBySession(sessionId)───────→│  │                │
     │←──[chunk0, chunk1, chunk2]─────│  │                │
     │                │                  │                │
     │──joinToString("\n\n")            │  │                │
     │──summarize(combinedText)───────────→│               │
     │                │                  │──generateContent→│
     │                │                  │←──summary───────│
     │←──summary───────────────────────│  │                │
     │                │                  │                │
     │──update uiState(summary)         │  │                │
     │──deleteBySession(sessionId)────→│  │                │
```

### 7.3 自己修復（クラッシュ復旧）

```
RecordingService     ChunkDao         RetryWorker
     │                  │                  │
     │ [クラッシュ発生]  │                  │
     │ [DB: UPLOADING残留]                  │
     │                  │                  │
     │──onCreate()───→│                  │
     │  resetStuckUploads()               │
     │──────────────────→│               │
     │  UPDATE SET FAILED │               │
     │  WHERE UPLOADING   │               │
     │                  │                  │
     │                  │  [15min後]       │
     │                  │──doWork()──────→│
     │                  │  getByStatus(FAILED)             │
     │                  │←────────────────│
     │                  │  再送処理...     │
```

---

## 8. エラー処理マトリクス

| シナリオ | 検知箇所 | 例外/条件 | 処理 | 要件ID |
|---|---|---|---|---|
| Groq 401 | TranscriptionRepo | HttpException(401) | FAILED + ログ | TR-001 |
| Groq 429 | TranscriptionRepo | HttpException(429) | FAILED → WorkManager再送 | TR-001 |
| Groq ネットワーク断 | TranscriptionRepo | IOException | FAILED → WorkManager再送 | ST-003 |
| Gemini タイムアウト | SummaryRepo | TimeoutCancellationException | UIにエラー表示 | SUM-001 |
| Gemini 空レスポンス | SummaryRepo | IllegalStateException | UIにエラー表示 | SUM-001 |
| ストレージ不足 | GaplessRecorder | IOException | 録音停止 + 通知 | REC-001 |
| AudioRecord初期化失敗 | GaplessRecorder | ERROR_BAD_VALUE | 例外スロー → Service停止 | REC-001 |
| ファイル消失 + 再送 | RetryWorker | !file.exists() | レコード削除 | ST-003 |
| クラッシュ後再起動 | RecordingService | UPLOADING残留 | resetStuckUploads() → FAILED | ST-002 |

---

## 9. BuildConfig設定（build.gradle.kts）

```kotlin
// app/build.gradle.kts（抜粋）

android {
    defaultConfig {
        // local.propertiesからAPIキー読込
        val localProps = java.util.Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) load(f.inputStream())
        }

        buildConfigField(
            "String", "GROQ_API_KEY",
            "\"${localProps.getProperty("groq.api.key", "")}\""
        )
        buildConfigField(
            "String", "GEMINI_API_KEY",
            "\"${localProps.getProperty("gemini.api.key", "")}\""
        )
    }
}
```

**local.properties フォーマット**:
```properties
groq.api.key=gsk_xxxxx
gemini.api.key=AIzaxxxxx
```

---

## 10. AndroidManifest.xml 設定

```xml
<!-- 必須権限 -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Application -->
<application
    android:name=".SummaryRecorderApp"
    ... >

    <service
        android:name=".service.RecordingService"
        android:foregroundServiceType="microphone"
        android:exported="false" />

</application>
```

---

## 11. 依存ライブラリ一覧（build.gradle.kts 抜粋）

```kotlin
dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gemini SDK
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.12")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.work:work-testing:2.9.0")
}
```
