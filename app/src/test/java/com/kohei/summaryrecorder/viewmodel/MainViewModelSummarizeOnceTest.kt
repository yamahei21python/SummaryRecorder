package com.kohei.summaryrecorder.viewmodel

import android.app.Application
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

/**
 * Bug fix verify: summarizeAll多重呼出し防止（take(1)）
 *
 * observeChunks()内でallDoneフィルタ分岐にtake(1)がないと、
 * DB再更新等で同じセッションのチャンクが再emitされた際に
 * summarizeAllが複数回走るバグ。
 * take(1)で最初のallDone検知時に1回のみ実行するよう修正済。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class MainViewModelSummarizeOnceTest {

    private lateinit var chunkRepository: ChunkRepository
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var recordingController: RecordingController

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        chunkRepository = mockk<ChunkRepository>(relaxed = true)
        summarizeUseCase = mockk<SummarizeUseCase>()
        chunksFlow = MutableStateFlow(emptyList())
        recordingController = mockk<RecordingController>(relaxed = true)
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

    @Test
    fun `summarizeAll is called exactly once even when chunks re-updated`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約テキスト")

        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()

        // 1回目: 全チャンクDONE
        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            doneChunk(id = 2, index = 1, text = "テキスト2")
        viewModel.stopRecording()
        advanceUntilIdle()
        
        // UnconfinedTestDispatcherでも念のため完了を待機
        val state1 = viewModel.uiState.value
        assertEquals("要約テキスト", state1.summary)

        io.mockk.coVerify(exactly = 1) { summarizeUseCase.execute(any()) }

        // 2回目: 同じ内容を再emit（DB再更新をシミュレーション）
        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            doneChunk(id = 2, index = 1, text = "テキスト2")
        )

        // まだ1回のみ — 2回目のsummarizeは発火しない
        io.mockk.coVerify(exactly = 1) { summarizeUseCase.execute(any()) }
    }

    @Test
    fun `summarizeAll not called when chunks are not all done`() = runTest {
        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()

        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1"),
            ChunkEntity(id = 2, sessionId = "test-session", chunkIndex = 1, filePath = "/chunk_1.wav", status = ChunkStatus.PENDING)
        )

        io.mockk.coVerify(exactly = 0) { summarizeUseCase.execute(any()) }
    }
}
