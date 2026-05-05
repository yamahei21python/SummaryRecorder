package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import javax.inject.Inject

class SummarizeUseCase @Inject constructor(
    private val chunkRepository: ChunkRepository,
    private val summaryRepo: SummaryRepository
) {
    suspend fun execute(sessionId: String): Result<String> {
        val chunks = chunkRepository.getBySession(sessionId)
        val combinedText = chunks
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n") { it.transcriptionText ?: "" }

        val result = summaryRepo.summarize(combinedText)
        if (result.isSuccess) {
            chunkRepository.deleteBySession(sessionId)
        }
        return result
    }
}
