package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class MainViewModelSummarizeOnceTest {

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

    private fun createViewModel() = MainViewModel(
        chunkRepository, summarizeUseCase, recordingController, savedStateHandle
    )

    @Test
    fun `summarizeAll is called exactly once even when chunks re-updated`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約テキスト")

        val viewModel = createViewModel()
        viewModel.startRecording()

        val doneChunks = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            doneChunk(id = 2, index = 1, text = "テキスト2")
        )

        // 録音中にDONEチャンクを流す → isRecording=true なので要約は発火しない
        chunksFlow.value = doneChunks
        advanceUntilIdle()

        // 録音停止
        viewModel.stopRecording()
        advanceUntilIdle()

        // stopRecording後にchunksFlowを再emit → collect再発火、isRecording=false + allDone で要約
        chunksFlow.value = doneChunks.map { it.copy(updatedAt = it.updatedAt + 1) }
        advanceUntilIdle()

        assertEquals("要約テキスト", viewModel.uiState.value.summary)
        io.mockk.coVerify(exactly = 1) { summarizeUseCase.execute(any()) }

        // 2回目: 同じ内容を再emit（DB再更新をシミュレーション）
        chunksFlow.value = doneChunks.map { it.copy(updatedAt = it.updatedAt + 2) }
        advanceUntilIdle()

        // まだ1回のみ — summarized=true でガード
        io.mockk.coVerify(exactly = 1) { summarizeUseCase.execute(any()) }
    }

    @Test
    fun `summarizeAll not called when chunks are not all done`() = runTest {
        val viewModel = createViewModel()
        viewModel.startRecording()

        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            ChunkEntity(id = 2, sessionId = "test-session", chunkIndex = 1, filePath = "/chunk_1.wav", status = ChunkStatus.PENDING)
        )

        io.mockk.coVerify(exactly = 0) { summarizeUseCase.execute(any()) }
    }
}
