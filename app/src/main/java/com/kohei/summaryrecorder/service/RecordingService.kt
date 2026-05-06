package com.kohei.summaryrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.kohei.summaryrecorder.R
import com.kohei.summaryrecorder.di.ChunkSize
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import com.kohei.summaryrecorder.service.TranscriptionUploader
import com.kohei.summaryrecorder.data.db.SummaryStatus
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = NotificationConstants.CHANNEL_ID
        private const val NOTIFICATION_ID = NotificationConstants.NOTIFICATION_ID
        private const val ACTION_START = "ACTION_START"
        private const val ACTION_STOP = "ACTION_STOP"
        private const val ACTION_PAUSE = "ACTION_PAUSE"
        private const val ACTION_RESUME = "ACTION_RESUME"
        private const val EXTRA_SESSION_ID = "session_id"

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

        fun pauseIntent(context: Context): Intent {
            return Intent(context, RecordingService::class.java).apply {
                action = ACTION_PAUSE
            }
        }

        fun resumeIntent(context: Context): Intent {
            return Intent(context, RecordingService::class.java).apply {
                action = ACTION_RESUME
            }
        }
    }

    @Inject lateinit var chunkRepository: ChunkRepository
    @Inject lateinit var uploader: TranscriptionUploader
    @Inject lateinit var chunkSize: ChunkSize
    @Inject lateinit var audioProvider: AudioProvider
    @Inject lateinit var summaryDao: com.kohei.summaryrecorder.data.db.SummaryDao
    @Inject lateinit var summarizeUseCase: SummarizeUseCase

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var recordingManager: RecordingManager

    override fun onCreate() {
        super.onCreate()
        recordingManager = RecordingManager(chunkRepository, uploader, serviceScope)
        serviceScope.launch {
            chunkRepository.resetStuckUploads()
            uploader.retryFailedChunks()
        }
        createNotificationChannel()
        BatteryOptimizer.checkAndNotify(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                    ?: UUID.randomUUID().toString()

                startForeground(NOTIFICATION_ID, buildNotification("録音中...", pauseAction = true),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        0
                    }
                )
                scheduleRetryWorker()

                val outputDir = File(filesDir, "recordings/$sessionId").also { it.mkdirs() }
                serviceScope.launch {
                    try {
                        recordingManager.startRecording(
                            sessionId = sessionId,
                            outputDir = outputDir,
                            chunkSizeBytes = chunkSize.bytes,
                            audioProvider = audioProvider
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("RecordingService", "startRecording failed, stopping service", e)
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                val sessionId = recordingManager.getCurrentSessionId()
                serviceScope.launch {
                    try {
                        recordingManager.stopRecording()
                    } catch (e: Exception) {
                        android.util.Log.w("RecordingService", "stopRecording failed", e)
                    }
                    if (sessionId != null) {
                        finalizeSession(sessionId)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                serviceScope.launch {
                    recordingManager.pauseRecording()
                }
                updateNotification("一時停止中...", pauseAction = false)
            }
            ACTION_RESUME -> {
                serviceScope.launch {
                    recordingManager.resumeRecording()
                }
                updateNotification("録音中...", pauseAction = true)
            }
        }
        return START_NOT_STICKY
    }

    private val binder = RecordingBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    inner class RecordingBinder : android.os.Binder() {
        fun getService(): RecordingService = this@RecordingService
        fun getCurrentSessionId(): String? = recordingManager.getCurrentSessionId()
        fun getVolumeLevel(): Float {
            val max = recordingManager.getMaxAmplitude()
            return (max.toFloat() / 32767f).coerceIn(0f, 1f)
        }
    }

    override fun onDestroy() {
        val sessionId = recordingManager.getCurrentSessionId()
        if (sessionId != null) {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(3000L) {
                        recordingManager.stopRecording()
                        finalizeSession(sessionId)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RecordingService", "onDestroy finalize failed", e)
                }
            }
        } else {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(2000L) {
                        recordingManager.stopRecording()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RecordingService", "onDestroy cleanup failed", e)
                }
            }
        }
        serviceScope.cancel()
        WorkManager.getInstance(this).cancelUniqueWork("retry_transcription")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "録音サービス", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String, pauseAction: Boolean = true) {
        val notification = buildNotification(text, pauseAction)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 録音停止後のセッション確定処理。
     * WavMerger → SummaryDao.insert(RECORDED) → SummarizeUseCase → DONE/ERROR → chunk DB削除
     */
    private suspend fun finalizeSession(sessionId: String) {
        try {
            val chunkDir = File(filesDir, "recordings/$sessionId")
            val chunkFiles = collectChunkFiles(chunkDir)

            if (chunkFiles.isEmpty()) {
                android.util.Log.w("RecordingService", "No chunk files for session $sessionId")
                return
            }

            val (mergedFile, durationMs) = mergeChunkFiles(chunkFiles, sessionId)
            insertSummaryRecord(sessionId, mergedFile, durationMs)
            executeSummarize(sessionId)
            cleanupChunks(sessionId, chunkDir)

        } catch (e: Exception) {
            android.util.Log.e("RecordingService", "finalizeSession failed", e)
            try {
                summaryDao.updateStatus(sessionId, SummaryStatus.ERROR, errorMessage = e.message)
            } catch (_: Exception) {}
        }
    }

    private suspend fun collectChunkFiles(chunkDir: File): List<File> {
        return withContext(Dispatchers.IO) {
            chunkDir.listFiles()
                ?.filter { it.name.endsWith(".wav") }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }

    private suspend fun mergeChunkFiles(chunkFiles: List<File>, sessionId: String): Pair<File, Long> {
        val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
        val mergedFile = File(recordingsDir, "$sessionId.wav")
        val durationMs = withContext(Dispatchers.IO) {
            com.kohei.summaryrecorder.recorder.WavMerger.merge(chunkFiles, mergedFile)
        }
        return Pair(mergedFile, durationMs)
    }

    private suspend fun insertSummaryRecord(sessionId: String, mergedFile: File, durationMs: Long) {
        summaryDao.insert(
            com.kohei.summaryrecorder.data.db.SummaryEntity(
                sessionId = sessionId,
                audioFilePath = mergedFile.absolutePath,
                durationMs = durationMs,
                status = SummaryStatus.RECORDED
            )
        )
    }

    private suspend fun executeSummarize(sessionId: String) {
        summaryDao.updateStatus(sessionId, SummaryStatus.SUMMARIZING)
        val result = summarizeUseCase.execute(sessionId)
        if (result.isSuccess) {
            val output = result.getOrThrow()
            summaryDao.updateStatusAndContent(
                sessionId = sessionId,
                status = SummaryStatus.DONE,
                title = output.summaryResult.title,
                summaryText = output.summaryResult.summaryText,
                transcriptionText = output.transcriptionText
            )
        } else {
            summaryDao.updateStatus(
                sessionId = sessionId,
                status = SummaryStatus.ERROR,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    private suspend fun cleanupChunks(sessionId: String, chunkDir: File) {
        chunkRepository.deleteBySession(sessionId)
        withContext(Dispatchers.IO) {
            val deleted = chunkDir.deleteRecursively()
            if (!deleted) {
                android.util.Log.w("RecordingService", "Failed to delete chunk dir: $chunkDir")
            }
        }
    }

    private fun buildNotification(text: String, pauseAction: Boolean = true): Notification {
        val intent = android.content.Intent(this, com.kohei.summaryrecorder.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val actionIntent = if (pauseAction) {
            PendingIntent.getService(
                this, 1, pauseIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this, 2, resumeIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val actionLabel = if (pauseAction) "一時停止" else "再開"
        val actionIcon = android.R.drawable.ic_media_pause

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SummaryRecorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(actionIcon, actionLabel, actionIntent)
            .build()
    }

    private fun scheduleRetryWorker() {
        val request = PeriodicWorkRequestBuilder<RetryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("retry_transcription", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
