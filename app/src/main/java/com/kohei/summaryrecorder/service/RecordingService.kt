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
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.di.ChunkSize
import com.kohei.summaryrecorder.domain.provider.AudioProvider
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.TranscriptionUploader
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
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
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

    @Inject lateinit var dao: ChunkDao
    @Inject lateinit var chunkRepository: ChunkRepository
    @Inject lateinit var uploader: TranscriptionUploader
    @Inject lateinit var chunkSize: ChunkSize
    @Inject lateinit var audioProvider: AudioProvider

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var recordingManager: RecordingManager

    override fun onCreate() {
        super.onCreate()
        recordingManager = RecordingManager(chunkRepository, uploader, serviceScope)
        serviceScope.launch { dao.resetStuckUploads() }
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
                recordingManager.startRecording(
                    sessionId = sessionId,
                    outputDir = outputDir,
                    chunkSizeBytes = chunkSize.bytes,
                    audioProvider = audioProvider
                )
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    recordingManager.stopRecording()
                    updateNotification("文字起こし処理中...")
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runBlocking { recordingManager.stopRecording() }
        serviceScope.cancel()
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

    private fun scheduleRetryWorker() {
        val request = PeriodicWorkRequestBuilder<RetryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("retry_transcription", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
