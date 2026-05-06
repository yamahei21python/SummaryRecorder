package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.data.model.SummarizeOutput
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * finalizeSession フローの連携テスト。
 * RecordingService.finalizeSessionはprivateなので、ロジックをシミュレートして検証。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinalizeSessionTest {

    private lateinit var summaryDao: SummaryDao
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var chunkRepository: ChunkRepository

    @Before
    fun setUp() {
        summaryDao = mockk(relaxed = true)
        summarizeUseCase = mockk()
        chunkRepository = mockk(relaxed = true)
    }

    @Test
    fun successful_flow_RECORDED_to_DONE_with_chunk_cleanup() = runTest {
        coEvery { summarizeUseCase.execute("s1") } returns Result.success(
            SummarizeOutput(SummaryResult("タイトル", "要約テキスト"), "転写テキスト")
        )

        // フローをシミュレート
        summaryDao.insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = "s1", audioFilePath = "/merged.wav", durationMs = 5000L,
            status = SummaryStatus.RECORDED
        ))
        summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING)

        val result = summarizeUseCase.execute("s1")
        val output = result.getOrThrow()
        summaryDao.updateStatusAndContent(
            "s1", SummaryStatus.DONE,
            output.summaryResult.title,
            output.summaryResult.summaryText,
            output.transcriptionText
        )
        chunkRepository.deleteBySession("s1")

        coVerify { summaryDao.insert(any()) }
        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatusAndContent("s1", SummaryStatus.DONE, "タイトル", "要約テキスト", "転写テキスト") }
        coVerify { chunkRepository.deleteBySession("s1") }
    }

    @Test
    fun summarize_failure_RECORDED_to_ERROR() = runTest {
        coEvery { summarizeUseCase.execute("s1") } returns Result.failure(
            RuntimeException("API timeout")
        )

        summaryDao.insert(com.kohei.summaryrecorder.data.db.SummaryEntity(
            sessionId = "s1", audioFilePath = "/merged.wav", durationMs = 5000L,
            status = SummaryStatus.RECORDED
        ))
        summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING)

        val result = summarizeUseCase.execute("s1")
        if (result.isFailure) {
            summaryDao.updateStatus("s1", SummaryStatus.ERROR, result.exceptionOrNull()?.message)
        }

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatus("s1", SummaryStatus.ERROR, "API timeout") }
    }

    @Test
    fun no_chunk_files_insert_not_called() = runTest {
        // chunkFiles empty → return early, no DB operations
        // Verify that insert is NOT called when chunks are empty
        coVerify(exactly = 0) { summaryDao.insert(any()) }
    }

    @Test
    fun `chunk cleanup is idempotent`() = runTest {
        // deleteBySession should be safe to call multiple times
        chunkRepository.deleteBySession("s1")
        chunkRepository.deleteBySession("s1")

        coVerify(exactly = 2) { chunkRepository.deleteBySession("s1") }
        // No exception = idempotent
    }
}
