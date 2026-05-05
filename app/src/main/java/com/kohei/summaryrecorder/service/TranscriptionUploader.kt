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
        chunkRepository.updateStatus(chunk.id, ChunkStatus.UPLOADING)
        val file = File(chunk.filePath)
        val result = try {
            transcriptionProvider.transcribe(file)
        } catch (e: Exception) {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.FAILED)
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

    suspend fun retryFailedChunks(): Int {
        val failedChunks = chunkRepository.getByStatus(ChunkStatus.FAILED)
        var processedCount = 0

        failedChunks.forEach { chunk ->
            val file = File(chunk.filePath)
            if (!file.exists()) {
                // ファイル欠損: 当該チャンクのみ削除。セッション全体は維持。
                chunkRepository.deleteById(chunk.id)
                return@forEach
            }

            if (uploadChunk(chunk).isSuccess) {
                processedCount++
            }
        }

        return processedCount
    }

    suspend fun getFailedChunkCount(): Int =
        chunkRepository.getByStatus(ChunkStatus.FAILED).size
}
