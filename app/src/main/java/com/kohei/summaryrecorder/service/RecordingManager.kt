package com.kohei.summaryrecorder.service

import android.util.Log
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.recorder.SimpleRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class RecordingManager(
    baseScope: CoroutineScope
) {
    private val recorderScope = CoroutineScope(baseScope.coroutineContext + Job())
    private val mutex = Mutex()
    private var recorder: SimpleRecorder? = null
    private var currentSessionId: String? = null
    private var currentAudioProvider: AudioProvider? = null
    private var currentOutputFile: File? = null

    suspend fun startRecording(
        sessionId: String,
        outputDir: File,
        audioProvider: AudioProvider
    ): File {
        mutex.withLock {
            recorder?.stop()
            currentSessionId = sessionId
            currentAudioProvider = audioProvider
            val newRecorder = SimpleRecorder(
                outputDir = outputDir,
                audioProvider = audioProvider,
                coroutineScope = recorderScope
            )
            recorder = newRecorder
            try {
                val file = newRecorder.start()
                currentOutputFile = file
                return file
            } catch (e: Exception) {
                recorder = null
                Log.e("RecordingManager", "startRecording failed", e)
                throw e
            }
        }
    }

    suspend fun stopRecording(): File? {
        return mutex.withLock {
            val file = recorder?.stop()
            currentOutputFile = file
            recorder = null
            currentSessionId = null
            currentAudioProvider = null
            file
        }
    }

    fun getCurrentSessionId(): String? = currentSessionId

    fun getMaxAmplitude(): Int = currentAudioProvider?.getMaxAmplitude() ?: 0

    suspend fun pauseRecording() {
        mutex.withLock { recorder?.pause() }
    }

    suspend fun resumeRecording() {
        mutex.withLock { recorder?.resume() }
    }
}
