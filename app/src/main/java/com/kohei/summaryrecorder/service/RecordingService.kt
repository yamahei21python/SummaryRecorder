package com.kohei.summaryrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.di.ServiceLocator
import com.kohei.summaryrecorder.recorder.GaplessRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Foreground Service: 長時間録音 + 即時アップロード。
 *
 * - onCreate: 自己修復(resetStuckUploads) + 通知チャンネル + バッテリー最適化確認
 * - onStartCommand(ACTION_START): 録音開始 + WorkManager登録
 * - onStartCommand(ACTION_STOP): 録音停止
 * - onDestroy: リソース解放
 */
class RecordingService : Service() {

    private companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_SESSION_ID = "session_id"
    }

    // DI（ServiceLocator経由）
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
        // 1. 自己修復（runBlockingで同期待ち）
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
        stopForeground(true)
        stopSelf()
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
