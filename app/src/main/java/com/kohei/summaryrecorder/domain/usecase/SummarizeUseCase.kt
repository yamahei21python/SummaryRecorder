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
        if (hasFailed) {
            return Result.failure(IllegalStateException("一部の録音データの書き起こしに失敗したため、要約できませんでした。"))
        }

        val combinedText = chunks
            .filter { it.status == ChunkStatus.DONE }
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n") { it.transcriptionText ?: "" }
            .trim()

        if (combinedText.isEmpty()) {
            return Result.success("録音データがありません")
        }

        return summaryRepo.summarize(combinedText)
    }
}
