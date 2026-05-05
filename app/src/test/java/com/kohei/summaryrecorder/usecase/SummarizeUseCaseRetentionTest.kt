package com.kohei.summaryrecorder.usecase

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bug fix verify: execute()成功時のdeleteBySession()削除
 *
 * 要約成功後にdeleteBySession()を呼ぶとDBからチャンクが消え、
 * 再要約や履歴参照が不可能になるバグ。
 * deleteBySession()呼び出し自体を削除してチャンクを保持するよう修正済。
 */
class SummarizeUseCaseRetentionTest {

    private lateinit var mockRepo: ChunkRepository
    private lateinit var mockSummaryRepo: SummaryRepository
    private lateinit var useCase: SummarizeUseCase

    @Before
    fun setUp() {
        mockRepo = mockk<ChunkRepository>(relaxed = true)
        mockSummaryRepo = mockk<SummaryRepository>(relaxed = true)
        useCase = SummarizeUseCase(mockRepo, mockSummaryRepo)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `execute preserves chunks after successful summarize`() = runTest {
        val chunks = listOf(
            ChunkEntity(sessionId = "s1", chunkIndex = 0, filePath = "/c0.wav", status = ChunkStatus.DONE, transcriptionText = "こんにちは"),
            ChunkEntity(sessionId = "s1", chunkIndex = 1, filePath = "/c1.wav", status = ChunkStatus.DONE, transcriptionText = "世界")
        )
        coEvery { mockRepo.getBySession("s1") } returns chunks
        coEvery { mockSummaryRepo.summarize(any()) } returns Result.success("要約結果")

        val result = useCase.execute("s1")

        assertTrue(result.isSuccess)
        assertEquals("要約結果", result.getOrThrow())

        // deleteBySessionが呼ばれていないこと（バグ修正の核心）
        coVerify(exactly = 0) { mockRepo.deleteBySession(any()) }
    }

    @Test
    fun `execute preserves chunks even on summarize failure`() = runTest {
        val chunks = listOf(
            ChunkEntity(sessionId = "s2", chunkIndex = 0, filePath = "/c0.wav", status = ChunkStatus.DONE, transcriptionText = "テスト")
        )
        coEvery { mockRepo.getBySession("s2") } returns chunks
        coEvery { mockSummaryRepo.summarize(any()) } returns Result.failure(RuntimeException("API Error"))

        val result = useCase.execute("s2")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { mockRepo.deleteBySession(any()) }
    }

    @Test
    fun `execute combines chunk texts in order`() = runTest {
        val chunks = listOf(
            ChunkEntity(sessionId = "s3", chunkIndex = 1, filePath = "/c1.wav", status = ChunkStatus.DONE, transcriptionText = "2番目"),
            ChunkEntity(sessionId = "s3", chunkIndex = 0, filePath = "/c0.wav", status = ChunkStatus.DONE, transcriptionText = "1番目")
        )
        coEvery { mockRepo.getBySession("s3") } returns chunks
        coEvery { mockSummaryRepo.summarize(any()) } returns Result.success("要約")

        useCase.execute("s3")

        // chunkIndex順で結合されること
        coVerify { mockSummaryRepo.summarize("1番目\n\n2番目") }
    }

    @Test
    fun `execute returns early when combinedText is empty`() = runTest {
        val chunks = listOf(
            ChunkEntity(sessionId = "s4", chunkIndex = 0, filePath = "/c0.wav", status = ChunkStatus.DONE, transcriptionText = "   ")
        )
        coEvery { mockRepo.getBySession("s4") } returns chunks

        val result = useCase.execute("s4")

        assertTrue(result.isSuccess)
        assertEquals("録音データがありません", result.getOrThrow())
        coVerify(exactly = 0) { mockSummaryRepo.summarize(any()) }
    }
}
