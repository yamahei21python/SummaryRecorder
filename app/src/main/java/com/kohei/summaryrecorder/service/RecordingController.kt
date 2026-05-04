package com.kohei.summaryrecorder.service

interface RecordingController {
    fun startRecording(sessionId: String)
    fun stopRecording()
}