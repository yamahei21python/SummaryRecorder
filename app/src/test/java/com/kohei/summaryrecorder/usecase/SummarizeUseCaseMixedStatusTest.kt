package com.kohei.summaryrecorder.usecase

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

class SummarizeUseCaseMixedStatusTest {

    private lateinit var mockChunkRepo: ChunkRepository
    private lateinit var mockSummaryProvider: SummaryProvider
    private lateinit var useCase: SummarizeUseCase

    @Before
    fun setUp() {
        mockChunkRepo = mockk()
        mockSummaryProvider = mockk()
        useCase = SummarizeUseCase(mockChunkRepo, mockSummaryProvider)
    }

    @Test
    fun `execute with mixed DONE and FAILED chunks includes error placeholder`() = runTest {
        val sessionId = "mixed-session"
        val chunks = listOf(
            ChunkEntity(sessionId, 0, "/path0", ChunkStatus.DONE, transcriptionText = "Text 0"),
            ChunkEntity(sessionId, 1, "/path1", ChunkStatus.FAILED, transcriptionText = "Failed Text"),
            ChunkEntity(sessionId, 2, "/path2", ChunkStatus.DONE, transcriptionText = "Text 2")
        )
        
        coEvery { mockChunkRepo.getBySession(sessionId) } returns chunks
        coEvery { mockSummaryProvider.summarize(any()) } returns Result.success(SummaryResult("タイトル", "Summary"))

        useCase.execute(sessionId)

        // FAILED チャンクは [音声認識エラー] に置換される
        coVerify { mockSummaryProvider.summarize("Text 0\n\n[音声認識エラー]\n\nText 2") }
    }
}
