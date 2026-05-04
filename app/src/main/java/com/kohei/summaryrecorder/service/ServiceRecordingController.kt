package com.kohei.summaryrecorder.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ServiceRecordingController @Inject constructor(
    @ApplicationContext private val context: Context
) : RecordingController {

    override fun startRecording(sessionId: String) {
        context.startService(RecordingService.startIntent(context, sessionId))
    }

    override fun stopRecording() {
        context.startService(RecordingService.stopIntent(context))
    }
}