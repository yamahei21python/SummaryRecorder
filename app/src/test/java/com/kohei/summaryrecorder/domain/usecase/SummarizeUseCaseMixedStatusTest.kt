package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummarizeUseCaseMixedStatusTest {

    private lateinit var mockRepo: ChunkRepository
    private lateinit var mockSummary: SummaryProvider
    private lateinit var useCase: SummarizeUseCase

    @Before
    fun setUp() {
        mockRepo = mockk()
        mockSummary = mockk()
        useCase = SummarizeUseCase(mockRepo, mockSummary)
    }

    @Test
    fun `execute with all DONE and some null transcriptionText returns empty data message`() = runTest {
        val chunk1 = ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.DONE, transcriptionText=null)
        val chunk2 = ChunkEntity(id=2, sessionId="s", chunkIndex=1, filePath="", status=ChunkStatus.DONE, transcriptionText="")
        
        coEvery { mockRepo.getBySession("s") } returns listOf(chunk1, chunk2)
        
        val result = useCase.execute("s")
        
        assertTrue(result.isSuccess)
        assertEquals("録音データがありません", result.getOrThrow())
    }

    @Test
    fun `execute with FAILED and DONE chunks returns summary with warning`() = runTest {
        val chunkDone = ChunkEntity(id=1, sessionId="s", chunkIndex=0, filePath="", status=ChunkStatus.DONE, transcriptionText="Hello")
        val chunkFailed = ChunkEntity(id=2, sessionId="s", chunkIndex=1, filePath="", status=ChunkStatus.FAILED, transcriptionText=null)
        
        coEvery { mockRepo.getBySession("s") } returns listOf(chunkDone, chunkFailed)
        coEvery { mockSummary.summarize("Hello") } returns Result.success("Summary")
        
        val result = useCase.execute("s")
        
        assertTrue(result.isSuccess)
        val text = result.getOrThrow()
        assertTrue(text.contains("一部の音声解析に失敗したため"))
        assertTrue(text.contains("Summary"))
    }
}
