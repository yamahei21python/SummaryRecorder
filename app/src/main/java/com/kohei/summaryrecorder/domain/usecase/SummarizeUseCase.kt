package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.data.model.SummarizeOutput
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import javax.inject.Inject

class SummarizeUseCase @Inject constructor(
    private val chunkRepository: ChunkRepository,
    private val summaryRepo: SummaryProvider
) {
    suspend fun execute(sessionId: String): Result<SummarizeOutput> {
        val chunks = chunkRepository.getBySession(sessionId)

        val hasFailed = chunks.any { it.status == ChunkStatus.FAILED }
        val combinedText = chunks
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n") { chunk ->
                when (chunk.status) {
                    ChunkStatus.DONE -> chunk.transcriptionText ?: ""
                    ChunkStatus.FAILED -> "[音声認識エラー]"
                    else -> ""
                }
            }
            .trim()

        if (combinedText.isEmpty()) {
            return if (hasFailed) {
                Result.failure(IllegalStateException("文字起こしに全て失敗しました"))
            } else {
                Result.failure(IllegalStateException("録音データがありません"))
            }
        }

        val summaryResult = summaryRepo.summarize(combinedText)
            .getOrElse { return Result.failure(it) }

        val result = SummarizeOutput(summaryResult, combinedText)

        return if (hasFailed) {
            // 一部失敗時は警告を付与
            Result.success(
                result.copy(
                    summaryResult = summaryResult.copy(
                        summaryText = "【注意】一部の音声解析に失敗したため、不完全な要約の可能性があります。\n\n${summaryResult.summaryText}"
                    )
                )
            )
        } else {
            Result.success(result)
        }
    }

    /**
     * B6/R1 修正: 3重実装を1箇所に集約。
     * execute() + DB永続化をワンストップで実行。
     */
    suspend fun executeAndPersist(sessionId: String, summaryDao: SummaryDao) {
        summaryDao.updateStatus(sessionId, SummaryStatus.SUMMARIZING)
        val result = execute(sessionId)
        if (result.isSuccess) {
            val output = result.getOrThrow()
            summaryDao.updateStatusAndContent(
                sessionId = sessionId,
                status = SummaryStatus.DONE,
                title = output.summaryResult.title,
                summaryText = output.summaryResult.summaryText,
                transcriptionText = output.transcriptionText
            )
        } else {
            summaryDao.updateStatus(
                sessionId = sessionId,
                status = SummaryStatus.ERROR,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }
}
