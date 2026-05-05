package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import javax.inject.Inject

/**
 * 要約ユースケース。
 * MainViewModelから分離。
 *
 * 責務:
 * - セッション内の全チャンク文字起こしテキストを結合
 * - SummaryRepository に要約依頼
 * - 要約成功時にセッションデータを削除
 */
class SummarizeUseCase @Inject constructor(
    private val dao: ChunkDao,
    private val summaryRepo: SummaryRepository
) {
    /**
     * 指定セッションの全DONEチャンクテキストを結合して要約。
     * 成功時にDB上のセッションデータを削除。
     *
     * @return 要約成功テキスト or 例外
     */
    suspend fun execute(sessionId: String): Result<String> {
        val chunks = dao.getBySession(sessionId)
        val combinedText = chunks
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n") { it.transcriptionText ?: "" }

        val result = summaryRepo.summarize(combinedText)
        if (result.isSuccess) {
            dao.deleteBySession(sessionId)
        }
        return result
    }
}
