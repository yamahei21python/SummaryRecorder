package com.kohei.summaryrecorder.service

import android.util.Log
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.recorder.GaplessRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RecordingManager(
    private val chunkRepository: ChunkRepository,
    private val uploader: TranscriptionUploader,
    baseScope: CoroutineScope
) {
    private val recorderScope = CoroutineScope(baseScope.coroutineContext + Job())
    private val uploadScope = CoroutineScope(baseScope.coroutineContext + SupervisorJob())
    private val mutex = Mutex()

    suspend fun startRecording(
        sessionId: String,
        outputDir: File,
        chunkSizeBytes: Long,
        audioProvider: AudioProvider
    ) {
        mutex.withLock {
            recorderRef.get()?.stop()
            val recorder = GaplessRecorder(
                outputDir = outputDir,
                chunkSizeBytes = chunkSizeBytes,
                onChunkComplete = { chunkIndex, file, isLast ->
                    onChunkRecorded(sessionId, chunkIndex, file, isLast)
                },
                audioProvider = audioProvider,
                coroutineScope = recorderScope
            )
            recorderRef.set(recorder)
            try {
                recorder.start()
            } catch (e: Exception) {
                recorderRef.set(null)
                Log.e("RecordingManager", "startRecording failed", e)
                throw e
            }
        }
    }

    suspend fun stopRecording() {
        mutex.withLock {
            recorderRef.getAndSet(null)?.stop()
        }
    }

    private suspend fun onChunkRecorded(sessionId: String, chunkIndex: Int, file: File, isLast: Boolean) {
        val entity = ChunkEntity(
            sessionId = sessionId,
            chunkIndex = chunkIndex,
            filePath = file.absolutePath,
            status = ChunkStatus.PENDING,
            isLast = isLast
        )
        try {
            val id = chunkRepository.insert(entity)
            if (id == -1L) {
                Log.e("RecordingManager", "Failed to insert chunk $chunkIndex into database")
                return
            }
            uploadScope.launch {
                val result = uploader.uploadChunk(entity.copy(id = id))
                if (result.isFailure) {
                    Log.w("RecordingManager", "uploadChunk failed: ${result.exceptionOrNull()?.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("RecordingManager", "onChunkRecorded failed", e)
        }
    }
}
