package com.kohei.summaryrecorder.domain.controller

interface RecordingController {
    fun startRecording(sessionId: String)
    fun stopRecording()
}