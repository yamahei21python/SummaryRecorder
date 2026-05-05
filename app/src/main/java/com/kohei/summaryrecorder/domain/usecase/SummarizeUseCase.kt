package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.domain.provider.SummaryProvider
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import javax.inject.Inject

class SummarizeUseCase @Inject constructor(
    private val chunkRepository: ChunkRepository,
    private val summaryRepo: SummaryProvider
) {
    suspend fun execute(sessionId: String): Result<String> {
        val chunks = chunkRepository.getBySession(sessionId)
        val combinedText = chunks
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n") { it.transcriptionText ?: "" }

        return summaryRepo.summarize(combinedText)
    }
}
