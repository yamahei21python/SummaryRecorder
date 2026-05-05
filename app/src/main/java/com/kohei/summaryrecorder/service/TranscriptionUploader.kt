package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import android.util.Log
import java.io.File
import javax.inject.Inject

class TranscriptionUploader @Inject constructor(
    private val chunkRepository: ChunkRepository,
    private val transcriptionProvider: TranscriptionProvider
) {

    suspend fun uploadChunk(chunk: ChunkEntity): Result<String> {
        val casResult = chunkRepository.casToUploading(chunk.id)
        if (casResult == 0) {
            return Result.failure(IllegalStateException("Chunk ${chunk.id} already being processed"))
        }
        val file = File(chunk.filePath)
        val result = try {
            transcriptionProvider.transcribe(file)
        } catch (e: Exception) {
            chunkRepository.casToFailed(chunk.id)
            return Result.failure(e)
        }
        return if (result.isSuccess) {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.DONE, result.getOrThrow())
            if (file.exists() && !file.delete()) {
                Log.w("TranscriptionUploader", "Failed to delete file: ${file.absolutePath}")
            }
            result
        } else {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.FAILED)
            result
        }
    }

    /**
     * 失敗チャンクを再アップロード。残失敗数を返す。
     */
    suspend fun retryFailedChunks(): Int {
        val failedChunks = chunkRepository.getByStatus(ChunkStatus.FAILED)

        failedChunks.forEach { chunk ->
            val file = File(chunk.filePath)
            if (!file.exists()) {
                chunkRepository.deleteById(chunk.id)
                return@forEach
            }

            uploadChunk(chunk)
        }

        return chunkRepository.getByStatus(ChunkStatus.FAILED).size
    }
}
