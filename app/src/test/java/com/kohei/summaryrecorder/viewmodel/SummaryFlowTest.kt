package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    private lateinit var chunkRepository: ChunkRepository
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var recordingController: RecordingController
    private lateinit var savedStateHandle: SavedStateHandle

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        chunkRepository = mockk<ChunkRepository>(relaxed = true)
        summarizeUseCase = mockk<SummarizeUseCase>()
        chunksFlow = MutableStateFlow(emptyList())
        recordingController = mockk<RecordingController>(relaxed = true)
        savedStateHandle = SavedStateHandle()
        every { chunkRepository.observeBySession(any()) } returns chunksFlow
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

    private fun createViewModel() = MainViewModel(
        chunkRepository, summarizeUseCase, recordingController, savedStateHandle
    )

    @Test
    fun `all done triggers summarize`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約テキスト")

        val viewModel = createViewModel()
        viewModel.startRecording()

        val doneChunks = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            doneChunk(id = 2, index = 1, text = "テキスト2")
        )

        // 録音中にDONEチャンクを流す
        chunksFlow.value = doneChunks
        advanceUntilIdle()

        // 録音停止
        viewModel.stopRecording()
        advanceUntilIdle()

        // stop後に再emit → isRecording=false + allDone で要約発火
        chunksFlow.value = doneChunks.map { it.copy(updatedAt = it.updatedAt + 1) }
        advanceUntilIdle()

        assertEquals("要約テキスト", viewModel.uiState.value.summary)
    }

    @Test
    fun `partial done does not trigger summarize`() = runTest {
        val viewModel = createViewModel()
        viewModel.startRecording()

        val partialChunks = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            pendingChunk(id = 2, index = 1)
        )
        chunksFlow.value = partialChunks

        assertNull(viewModel.uiState.value.summary)
    }

    @Test
    fun `empty chunks does not trigger summarize`() = runTest {
        val viewModel = createViewModel()
        viewModel.startRecording()

        chunksFlow.value = emptyList()

        assertNull(viewModel.uiState.value.summary)
    }

    @Test
    fun `summarize failure shows error`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(
            RuntimeException("API error")
        )

        val viewModel = createViewModel()
        viewModel.startRecording()

        val doneChunks = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        // 録音中にDONEチャンクを流す
        chunksFlow.value = doneChunks
        advanceUntilIdle()

        // 録音停止
        viewModel.stopRecording()
        advanceUntilIdle()

        // stop後に再emit → isRecording=false + allDone で要約発火（失敗）
        chunksFlow.value = doneChunks.map { it.copy(updatedAt = it.updatedAt + 1) }
        advanceUntilIdle()

        val error = viewModel.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("API error"))
    }
}
