package com.kohei.summaryrecorder.domain.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface RecordingController {
    fun startRecording(sessionId: String)
    fun stopRecording()
    fun pauseRecording()
    fun resumeRecording()
    val currentSessionId: String? get() = null
    val currentVolumeLevel: Float get() = 0f
    val isReady: StateFlow<Boolean> get() = MutableStateFlow(true)
    suspend fun awaitReady()
}
