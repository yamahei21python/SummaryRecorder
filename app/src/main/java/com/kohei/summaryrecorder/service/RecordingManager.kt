package com.kohei.summaryrecorder.service

import android.util.Log
import com.kohei.summaryrecorder.domain.provider.AudioProvider
import com.kohei.summaryrecorder.domain.usecase.TranscriptionUploader
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.recorder.GaplessRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 録音制御マネージャー。
 * RecordingServiceから録音開始/停止/チャンク処理を分離。
 */
class RecordingManager(
    private val dao: ChunkDao,
    private val uploader: TranscriptionUploader,
    private val serviceScope: CoroutineScope
) {
    private var recorder: GaplessRecorder? = null
    private var sessionId: String = ""

    fun startRecording(
        sessionId: String,
        outputDir: File,
        chunkSizeBytes: Long,
        audioProvider: AudioProvider
    ) {
        this.sessionId = sessionId

        recorder = GaplessRecorder(
            outputDir = outputDir,
            chunkSizeBytes = chunkSizeBytes,
            onChunkComplete = { chunkIndex, file ->
                serviceScope.launch {
                    onChunkRecorded(chunkIndex, file)
                }
            },
            audioProvider = audioProvider,
            coroutineScope = serviceScope
        ).also { it.start() }
    }

    /**
     * 録音停止。suspend（GaplessRecorder.stop()がsuspendのため）。
     * RecordingServiceのonDestroyからserviceScope.launchで呼ぶ。
     */
    suspend fun stopRecording() {
        recorder?.stop()
        recorder = null
    }

    private suspend fun onChunkRecorded(chunkIndex: Int, file: File) {
        val entity = ChunkEntity(
            sessionId = sessionId,
            chunkIndex = chunkIndex,
            filePath = file.absolutePath,
            status = ChunkStatus.PENDING
        )
        try {
            val id = dao.insert(entity)
            val result = uploader.uploadChunk(entity.copy(id = id))
            if (result.isFailure) {
                Log.w("RecordingManager", "uploadChunk failed: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.w("RecordingManager", "onChunkRecorded failed", e)
        }
    }
}
