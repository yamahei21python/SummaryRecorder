package com.kohei.summaryrecorder.service

import android.util.Log
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.recorder.GaplessRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class RecordingManager(
    private val chunkRepository: ChunkRepository,
    private val uploader: TranscriptionUploader,
    private val serviceScope: CoroutineScope
) {
    private val recorderRef = AtomicReference<GaplessRecorder?>(null)

    suspend fun startRecording(
        sessionId: String,
        outputDir: File,
        chunkSizeBytes: Long,
        audioProvider: AudioProvider
    ) {
        recorderRef.get()?.stop()
        val recorder = GaplessRecorder(
            outputDir = outputDir,
            chunkSizeBytes = chunkSizeBytes,
            onChunkComplete = { chunkIndex, file ->
                serviceScope.launch {
                    onChunkRecorded(sessionId, chunkIndex, file)
                }
            },
            audioProvider = audioProvider,
            coroutineScope = serviceScope
        )
        recorderRef.set(recorder)
        recorder.start()
    }

    suspend fun stopRecording() {
        recorderRef.getAndSet(null)?.stop()
    }

    private suspend fun onChunkRecorded(sessionId: String, chunkIndex: Int, file: File) {
        val entity = ChunkEntity(
            sessionId = sessionId,
            chunkIndex = chunkIndex,
            filePath = file.absolutePath,
            status = ChunkStatus.PENDING
        )
        try {
            val id = chunkRepository.insert(entity)
            if (id == -1L) {
                Log.e("RecordingManager", "Failed to insert chunk $chunkIndex into database")
                return
            }
            val result = uploader.uploadChunk(entity.copy(id = id))
            if (result.isFailure) {
                Log.w("RecordingManager", "uploadChunk failed: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.w("RecordingManager", "onChunkRecorded failed", e)
        }
    }
}
