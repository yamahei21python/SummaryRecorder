package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.domain.controller.RecordingController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class SummaryFlowTest {

    private lateinit var dao: ChunkDao
    private lateinit var summaryRepo: SummaryRepository
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var recordingController: RecordingController

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dao = mockk<ChunkDao>(relaxed = true)
        summaryRepo = mockk<SummaryRepository>()
        chunksFlow = MutableStateFlow(emptyList())
        recordingController = mockk<RecordingController>(relaxed = true)
        coEvery { dao.observeBySession(any()) } returns chunksFlow
        every { recordingController.startRecording(any()) } returns Unit
        every { recordingController.stopRecording() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun doneChunk(id: Long, index: Int, text: String) = ChunkEntity(
        id = id,
        sessionId = "test-session",
        chunkIndex = index,
        filePath = "/chunk_$index.wav",
        status = ChunkStatus.DONE,
        transcriptionText = text
    )

    private fun pendingChunk(id: Long, index: Int) = ChunkEntity(
        id = id,
        sessionId = "test-session",
        chunkIndex = index,
        filePath = "/chunk_$index.wav",
        status = ChunkStatus.PENDING
    )

    @Test
    fun `all done triggers summarize`() = runTest {
        coEvery { summaryRepo.summarize(any()) } returns Result.success("要約テキスト")

        val viewModel = MainViewModel(dao, summaryRepo, recordingController)
        viewModel.startRecording()

        val doneChunks = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            doneChunk(id = 2, index = 1, text = "テキスト2")
        )
        chunksFlow.value = doneChunks

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("要約テキスト", state.summary)
        }

        coVerify { dao.deleteBySession(any()) }
    }

    @Test
    fun `partial done does not trigger summarize`() = runTest {
        val viewModel = MainViewModel(dao, summaryRepo, recordingController)
        viewModel.startRecording()

        val partialChunks = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            pendingChunk(id = 2, index = 1)
        )
        chunksFlow.value = partialChunks

        assertNull(viewModel.uiState.value.summary)
        coVerify(exactly = 0) { summaryRepo.summarize(any()) }
    }

    @Test
    fun `empty chunks does not trigger summarize`() = runTest {
        val viewModel = MainViewModel(dao, summaryRepo, recordingController)
        viewModel.startRecording()

        chunksFlow.value = emptyList()

        assertNull(viewModel.uiState.value.summary)
        coVerify(exactly = 0) { summaryRepo.summarize(any()) }
    }

    @Test
    fun `summarize failure shows error`() = runTest {
        coEvery { summaryRepo.summarize(any()) } returns Result.failure(
            RuntimeException("API error")
        )

        val viewModel = MainViewModel(dao, summaryRepo, recordingController)
        viewModel.startRecording()

        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        val error = viewModel.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("API error"))
        coVerify(exactly = 0) { dao.deleteBySession(any()) }
    }
}
