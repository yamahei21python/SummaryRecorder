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
        context.startService(RecordingService.stopIntent(context))
    }

    override fun pauseRecording() {
        context.startService(RecordingService.pauseIntent(context))
    }

    override fun resumeRecording() {
        context.startService(RecordingService.resumeIntent(context))
    }
}
