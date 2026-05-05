package com.kohei.summaryrecorder.service

import android.content.Context
import com.kohei.summaryrecorder.domain.controller.RecordingController
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ServiceRecordingController @Inject constructor(
    @ApplicationContext private val context: Context
) : RecordingController {

    override fun startRecording(sessionId: String) {
        ContextCompat.startForegroundService(context, RecordingService.startIntent(context, sessionId))
    }

    override fun stopRecording() {
        context.stopService(android.content.Intent(context, RecordingService::class.java))
    }
}