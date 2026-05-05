package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import com.kohei.summaryrecorder.domain.provider.TranscriptionProvider
import java.io.File

class TranscriptionUploader(
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
            file.delete()
            result
        } else {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.FAILED)
            result
        }
    }

    suspend fun retryFailedChunks(): Int {
        val failedChunks = chunkRepository.getByStatus(ChunkStatus.FAILED)
        val bySession = failedChunks.groupBy { it.sessionId }
        var processedCount = 0

        bySession.forEach { (sessionId, chunks) ->
            chunks.forEach { chunk ->
                val file = File(chunk.filePath)
                if (!file.exists()) {
                    chunkRepository.deleteBySession(sessionId)
                    return@forEach
                }

                val success = processTranscribeResult(chunk, updateUploading = true)
                if (success) processedCount++
            }
        }

        return processedCount
    }

    suspend fun getFailedChunkCount(): Int =
        chunkRepository.getByStatus(ChunkStatus.FAILED).size

    private suspend fun processTranscribeResult(
        chunk: ChunkEntity,
        updateUploading: Boolean = false
    ): Boolean {
        val file = File(chunk.filePath)
        if (updateUploading) {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.UPLOADING)
        }

        val result = try {
            transcriptionProvider.transcribe(file)
        } catch (e: Exception) {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.FAILED)
            return false
        }

        return if (result.isSuccess) {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.DONE, result.getOrThrow())
            file.delete()
            true
        } else {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.FAILED)
            false
        }
    }
}
