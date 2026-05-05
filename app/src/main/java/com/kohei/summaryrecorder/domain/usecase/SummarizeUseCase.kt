package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import javax.inject.Inject

class SummarizeUseCase @Inject constructor(
    private val chunkRepository: ChunkRepository,
    private val summaryRepo: SummaryProvider
) {
    suspend fun execute(sessionId: String): Result<String> {
        val chunks = chunkRepository.getBySession(sessionId)
        
        val hasFailed = chunks.any { it.status == ChunkStatus.FAILED }
        val combinedText = chunks
            .filter { it.status == ChunkStatus.DONE }
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n") { it.transcriptionText ?: "" }
            .trim()

        if (combinedText.isEmpty()) {
            return if (hasFailed) {
                Result.failure(IllegalStateException("文字起こしに全て失敗しました"))
            } else {
                Result.success("録音データがありません")
            }
        }

        val result = summaryRepo.summarize(combinedText)
        
        return if (hasFailed && result.isSuccess) {
            Result.success("【注意】一部の音声解析に失敗したため、不完全な要約の可能性があります。\n\n${result.getOrThrow()}")
        } else {
            result
        }
    }
}
