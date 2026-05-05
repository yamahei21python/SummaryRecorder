package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        
        if (!file.exists()) {
            chunkRepository.casToFailed(chunk.id)
            return Result.failure(FileNotFoundException("File not found: ${file.absolutePath}"))
        }
        
        if (file.exists() && file.length() <= 44L) {
            chunkRepository.updateStatus(chunk.id, ChunkStatus.DONE, "")
            file.delete()
            return Result.success("")
        }

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
            // BUG-004: 状態の不整合を防ぐため、単純なupdateStatusではなくcasToFailedを使用
            chunkRepository.casToFailed(chunk.id)
            result
        }
    }

    /**
     * 失敗チャンクを再アップロード。残失敗数を返す。
     * REF-002: 直列処理を並列処理に変更
     */
    suspend fun retryFailedChunks(): Int = coroutineScope {
        // BUG-01: FAILEDだけでなくPENDINGチャンクも救済対象に含める
        val targets = chunkRepository.getByStatus(ChunkStatus.FAILED) + 
                      chunkRepository.getByStatus(ChunkStatus.PENDING)

        targets.map { chunk ->
            async {
                val file = File(chunk.filePath)
                if (!file.exists()) {
                    chunkRepository.deleteById(chunk.id)
                } else {
                    uploadChunk(chunk)
                }
            }
        }.awaitAll()

        chunkRepository.getByStatus(ChunkStatus.FAILED).size +
        chunkRepository.getByStatus(ChunkStatus.PENDING).size
    }
}
