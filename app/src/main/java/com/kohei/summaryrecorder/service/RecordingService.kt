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
import com.kohei.summaryrecorder.service.TranscriptionUploader
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
    }

    @Inject lateinit var chunkRepository: ChunkRepository
    @Inject lateinit var uploader: TranscriptionUploader
    @Inject lateinit var chunkSize: ChunkSize
    @Inject lateinit var audioProvider: AudioProvider

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var recordingManager: RecordingManager

    override fun onCreate() {
        super.onCreate()
        recordingManager = RecordingManager(chunkRepository, uploader, serviceScope)
        // #3: クラッシュ残留UPLOADINGをFAILEDにリセット後、即時リトライ
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

                startForeground(NOTIFICATION_ID, buildNotification("録音中..."),
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
                serviceScope.launch {
                    try {
                        recordingManager.stopRecording()
                    } catch (e: Exception) {
                        android.util.Log.w("RecordingService", "stopRecording failed", e)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 先にファイナライズ（WAVヘッダー書込み等）→ その後にスコープキャンセル
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    recordingManager.stopRecording()
                }
            } catch (e: Exception) {
                android.util.Log.w("RecordingService", "Cleanup failed", e)
            }
        }
        serviceScope.cancel()
        // #1: WorkManagerのRetryWorkerをキャンセル（不要な定期実行を防止）
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

    private fun buildNotification(text: String): Notification {
        val intent = android.content.Intent(this, com.kohei.summaryrecorder.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SummaryRecorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
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
