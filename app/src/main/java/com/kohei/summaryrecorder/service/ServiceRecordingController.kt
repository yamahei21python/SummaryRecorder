package com.kohei.summaryrecorder.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.kohei.summaryrecorder.domain.controller.RecordingController
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ServiceRecordingController @Inject constructor(
    @ApplicationContext private val context: Context
) : RecordingController {

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady

    private var binder: RecordingService.RecordingBinder? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? RecordingService.RecordingBinder
            _isReady.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            _isReady.value = false
        }
    }

    private var isBound = false

    private fun ensureBound() {
        if (!isBound) {
            val intent = Intent(context, RecordingService::class.java)
            isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbind() {
        if (isBound) {
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
            binder = null
            _isReady.value = false
        }
    }

    override fun startRecording(sessionId: String) {
        ContextCompat.startForegroundService(context, RecordingService.startIntent(context, sessionId))
        ensureBound()
    }

    override fun stopRecording() {
        context.startService(RecordingService.stopIntent(context))
    }

    override fun pauseRecording() {
        context.startService(RecordingService.pauseIntent(context))
    }

    override fun resumeRecording() {
        context.startService(RecordingService.resumeIntent(context))
    }

    override val currentSessionId: String?
        get() = binder?.getCurrentSessionId()

    override val currentVolumeLevel: Float
        get() = binder?.getVolumeLevel() ?: 0f

    override suspend fun awaitReady() {
        if (_isReady.value) return
        _isReady.first { it }
    }
}
