package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummarizeUseCaseFallbackTest {

    private lateinit var mockRepo: ChunkRepository
    private lateinit var mockSummary: SummaryProvider
    private lateinit var useCase: SummarizeUseCase

    @Before
    fun setUp() {
        mockRepo = mockk()
        mockSummary = mockk()
        useCase = SummarizeUseCase(mockRepo, mockSummary)
    }

    // ===== Empty text cases =====

    @Test
    fun `execute with empty chunks list returns failure`() = runTest {
        coEvery { mockRepo.getBySession("s") } returns emptyList()

        val result = useCase.execute("s")

        assertTrue(result.isFailure)
        assertEquals("録音データがありません", result.exceptionOrNull()?.message)
    }

    @Test
    fun `execute with all PENDING chunks returns failure`() = runTest {
        val chunks = listOf(
            ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.PENDING),
            ChunkEntity(id=2, sessionId="s", chunkIndex=1, filePath="", status=ChunkStatus.PENDING)
        )
        coEvery { mockRepo.getBySession("s") } returns chunks

        val result = useCase.execute("s")

        assertTrue(result.isFailure)
        assertEquals("録音データがありません", result.exceptionOrNull()?.message)
    }

    @Test
    fun `execute with all UPLOADING chunks returns failure`() = runTest {
        val chunks = listOf(
            ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.UPLOADING)
        )
        coEvery { mockRepo.getBySession("s") } returns chunks

        val result = useCase.execute("s")

        assertTrue(result.isFailure)
        assertEquals("録音データがありません", result.exceptionOrNull()?.message)
    }

    // ===== All FAILED case =====

    @Test
    fun `execute with all FAILED chunks returns warning summary`() = runTest {
        // FAILED→"[音声認識エラー]" each → combined is NOT empty → summarize called
        val chunks = listOf(
            ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.FAILED),
            ChunkEntity(id=2, sessionId="s", chunkIndex=1, filePath="", status=ChunkStatus.FAILED)
        )
        coEvery { mockRepo.getBySession("s") } returns chunks
        coEvery { mockSummary.summarize(any()) } returns Result.success(SummaryResult("タイトル", "要約"))

        val result = useCase.execute("s")

        assertTrue(result.isSuccess)
        val output = result.getOrThrow()
        assertTrue(output.summaryResult.summaryText.contains("【注意】"))
        assertTrue(output.transcriptionText.contains("[音声認識エラー]"))
    }

    @Test
    fun `execute with DONE null transcription and FAILED returns warning summary`() = runTest {
        // DONE(null text)→"", FAILED→"[音声認識エラー]" → combined="[音声認識エラー]" → not empty
        val chunks = listOf(
            ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.DONE, transcriptionText=null),
            ChunkEntity(id=2, sessionId="s", chunkIndex=1, filePath="", status=ChunkStatus.FAILED)
        )
        coEvery { mockRepo.getBySession("s") } returns chunks
        coEvery { mockSummary.summarize(any()) } returns Result.success(SummaryResult("t", "summary"))

        val result = useCase.execute("s")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().summaryResult.summaryText.contains("【注意】"))
        assertTrue(result.getOrThrow().transcriptionText.contains("[音声認識エラー]"))
    }

    // ===== Mixed status with warning =====

    @Test
    fun `execute with DONE and FAILED prepends warning to summary`() = runTest {
        val chunks = listOf(
            ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.DONE, transcriptionText="こんにちは"),
            ChunkEntity(id=2, sessionId="s", chunkIndex=1, filePath="", status=ChunkStatus.FAILED, transcriptionText=null)
        )
        coEvery { mockRepo.getBySession("s") } returns listOf(
            chunks[0], chunks[1]
        )
        coEvery { mockSummary.summarize(any()) } returns Result.success(SummaryResult("会議", "重要な内容"))

        val result = useCase.execute("s")

        assertTrue(result.isSuccess)
        val output = result.getOrThrow()
        assertTrue(output.summaryResult.summaryText.startsWith("【注意】"))
        assertTrue(output.summaryResult.summaryText.contains("重要な内容"))
        // transcriptionは結合テキスト
        assertTrue(output.transcriptionText.contains("こんにちは"))
        assertTrue(output.transcriptionText.contains("[音声認識エラー]"))
    }

    // ===== SummaryProvider failure =====

    @Test
    fun `execute when summaryProvider fails returns failure`() = runTest {
        val chunks = listOf(
            ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.DONE, transcriptionText="テキスト")
        )
        coEvery { mockRepo.getBySession("s") } returns chunks
        coEvery { mockSummary.summarize(any()) } returns Result.failure(RuntimeException("API error"))

        val result = useCase.execute("s")

        assertTrue(result.isFailure)
        assertEquals("API error", result.exceptionOrNull()?.message)
    }

    // ===== Ordering =====

    @Test
    fun `execute sorts chunks by chunkIndex before joining`() = runTest {
        val chunks = listOf(
            ChunkEntity(id=2, sessionId="s", chunkIndex=1, filePath="", status=ChunkStatus.DONE, transcriptionText="二番目"),
            ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.DONE, transcriptionText="一番目")
        )
        coEvery { mockRepo.getBySession("s") } returns chunks
        coEvery { mockSummary.summarize(any()) } returns Result.success(SummaryResult("t", "s"))

        val result = useCase.execute("s")

        assertTrue(result.isSuccess)
        // transcriptionTextは順序通り "一番目\n\n二番目"
        assertEquals("一番目\n\n二番目", result.getOrThrow().transcriptionText)
    }
}
