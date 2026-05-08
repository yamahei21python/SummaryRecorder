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
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.data.db.SummaryStatus
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
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

    @Inject lateinit var audioProvider: AudioProvider
    @Inject lateinit var transcriptionProvider: TranscriptionProvider
    @Inject lateinit var summaryProvider: SummaryProvider
    @Inject lateinit var summaryDao: com.kohei.summaryrecorder.data.db.SummaryDao

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var recordingManager: RecordingManager

    override fun onCreate() {
        super.onCreate()
        recordingManager = RecordingManager(serviceScope)
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

                val outputDir = File(filesDir, "recordings").also { it.mkdirs() }
                serviceScope.launch {
                    try {
                        recordingManager.startRecording(
                            sessionId = sessionId,
                            outputDir = outputDir,
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
                        val wavFile = recordingManager.stopRecording()
                        if (sessionId != null && wavFile != null) {
                            finalizeSession(sessionId, wavFile)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("RecordingService", "stopRecording failed", e)
                        if (sessionId != null) {
                            try {
                                summaryDao.updateStatus(sessionId, SummaryStatus.ERROR, errorMessage = e.message)
                            } catch (_: Exception) {}
                        }
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
                    val wavFile = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                        recordingManager.stopRecording()
                    }
                    if (wavFile != null) {
                        finalizeSession(sessionId, wavFile)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RecordingService", "onDestroy finalize failed", e)
                }
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun finalizeSession(sessionId: String, wavFile: File) {
        try {
            val durationMs = calcDuration(wavFile)
            summaryDao.insert(
                com.kohei.summaryrecorder.data.db.SummaryEntity(
                    sessionId = sessionId,
                    audioFilePath = wavFile.absolutePath,
                    durationMs = durationMs,
                    status = SummaryStatus.RECORDED
                )
            )
            summaryDao.updateStatus(sessionId, SummaryStatus.SUMMARIZING)

            // 文字起こし
            val transcriptionResult = transcriptionProvider.transcribe(wavFile)
            if (transcriptionResult.isFailure) {
                summaryDao.updateStatus(sessionId, SummaryStatus.ERROR, errorMessage = transcriptionResult.exceptionOrNull()?.message)
                return
            }
            val transcriptionText = transcriptionResult.getOrThrow()

            // 要約
            val summaryResult = summaryProvider.summarize(transcriptionText)
            if (summaryResult.isFailure) {
                summaryDao.updateStatus(sessionId, SummaryStatus.ERROR, errorMessage = summaryResult.exceptionOrNull()?.message)
                return
            }
            val output = summaryResult.getOrThrow()

            summaryDao.updateStatusAndContent(
                sessionId = sessionId,
                status = SummaryStatus.DONE,
                title = output.title,
                summaryText = output.summaryText,
                transcriptionText = transcriptionText
            )
        } catch (e: Exception) {
            android.util.Log.e("RecordingService", "finalizeSession failed", e)
            try {
                summaryDao.updateStatus(sessionId, SummaryStatus.ERROR, errorMessage = e.message)
            } catch (_: Exception) {}
        }
    }

    private fun calcDuration(file: File): Long {
        val pcmBytes = file.length() - 44
        if (pcmBytes <= 0) return 0L
        val samples = pcmBytes / 2 // 16bit mono = 2 bytes per sample
        return samples * 1000L / 16000 // 16kHz
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
}
