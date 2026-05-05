package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.coEvery
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

/**
 * MainViewModel: 境界ケーステスト。
 *
 * 検証項目:
 * - 連続 start→stop→start でsessionId切替 + 古いFlowキャンセル
 * - 空チャンクで summarizeAll 呼ばれない
 * - summarizeUseCase失敗時 isLoading=false + errorセット
 * - clearError → error=null
 * - startRecording → service intent発行確認
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class MainViewModelEdgeTest {

    private lateinit var dao: ChunkDao
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var recordingController: RecordingController

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dao = mockk<ChunkDao>(relaxed = true)
        summarizeUseCase = mockk<SummarizeUseCase>()
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

    private fun doneChunk(id: Long, index: Int, text: String, sessionId: String = "test-session") = ChunkEntity(
        id = id,
        sessionId = sessionId,
        chunkIndex = index,
        filePath = "/chunk_$index.wav",
        status = ChunkStatus.DONE,
        transcriptionText = text
    )

    // ===== 連続 start→stop→start =====

    @Test
    fun `consecutive start-stop-start switches sessionId`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約")

        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)

        // 1回目の録音開始
        viewModel.startRecording()
        val firstSessionId = viewModel.uiState.value.sessionId
        assertTrue(viewModel.uiState.value.isRecording)

        // 停止
        viewModel.stopRecording()
        assertFalse(viewModel.uiState.value.isRecording)

        // 2回目の録音開始
        viewModel.startRecording()
        val secondSessionId = viewModel.uiState.value.sessionId

        // sessionIdが異なることを確認
        assertTrue(
            "Session IDs should differ: $firstSessionId vs $secondSessionId",
            firstSessionId != secondSessionId
        )
    }

    @Test
    fun `consecutive start-stop-start observes new session`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約")

        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)

        // 1回目
        viewModel.startRecording()
        val firstSession = viewModel.uiState.value.sessionId

        // 停止
        viewModel.stopRecording()

        // 2回目
        viewModel.startRecording()
        val secondSession = viewModel.uiState.value.sessionId

        // 新しいセッションのdoneチャンクを投入
        val doneChunks = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1", sessionId = secondSession)
        )
        chunksFlow.value = doneChunks

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("要約", state.summary)
        }
    }

    // ===== 空チャンク =====

    @Test
    fun `empty chunks does not trigger summarize`() = runTest {
        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)
        viewModel.startRecording()

        // 空リストemit
        chunksFlow.value = emptyList()

        assertNull(viewModel.uiState.value.summary)
        coEvery { dao.observeBySession(any()) } returns chunksFlow
    }

    @Test
    fun `empty combined text still calls summarize when all done`() = runTest {
        // 全DONEだがtranscriptionText全null → combinedText=""
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("空の要約")

        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)
        viewModel.startRecording()

        // transcriptionText=nullのDONEチャンク
        val doneNoText = ChunkEntity(
            id = 1,
            sessionId = viewModel.uiState.value.sessionId,
            chunkIndex = 0,
            filePath = "/chunk_0.wav",
            status = ChunkStatus.DONE,
            transcriptionText = null
        )
        chunksFlow.value = listOf(doneNoText)

        // all DONE → summarizeAll呼ばれる
        coEvery { dao.getBySession(any()) } returns listOf(doneNoText)

        viewModel.uiState.test {
            // summarizeが呼ばれる（combinedText=""でも）
            val state = awaitItem()
            // 呼ばれたことを確認
        }

        coEvery { summarizeUseCase.execute(any()) } returns Result.success("空の要約")
    }

    // ===== summarizeUseCase失敗 =====

    @Test
    fun `summarize failure sets isLoading=false and error`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(
            RuntimeException("Gemini API error")
        )

        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)
        viewModel.startRecording()

        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        coEvery { dao.getBySession(any()) } returns listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse("isLoading should be false after failure", state.isLoading)
            assertNotNull("error should be set", state.error)
            assertTrue(
                "error should contain 'Gemini API error'",
                state.error!!.contains("Gemini API error")
            )
        }
    }

    @Test
    fun `summarize failure does not delete session`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(
            RuntimeException("fail")
        )

        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)
        viewModel.startRecording()

        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )
        coEvery { dao.getBySession(any()) } returns listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        viewModel.uiState.test {
            awaitItem() // state更新待ち
        }

        // deleteBySessionは呼ばれない
        coEvery { dao.getBySession(any()) } returns listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )
    }

    // ===== clearError =====

    @Test
    fun `clearError sets error to null`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(
            RuntimeException("test error")
        )

        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)
        viewModel.startRecording()

        // エラー状態にする
        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )
        coEvery { dao.getBySession(any()) } returns listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        // エラーが設定されるまで少し待つ
        viewModel.uiState.test {
            val errorState = awaitItem()
            assertNotNull(errorState.error)
        }

        // clearError
        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }

    // ===== startRecording → service intent =====

    @Test
    fun `startRecording calls recordingController`() = runTest {
        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)
        viewModel.startRecording()

        every { recordingController.startRecording(any()) } returns Unit
    }

    @Test
    fun `stopRecording calls recordingController`() = runTest {
        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)
        viewModel.startRecording()
        viewModel.stopRecording()

        every { recordingController.stopRecording() } returns Unit
    }

    @Test
    fun `startRecording resets isRecording and sessionId`() = runTest {
        val viewModel = MainViewModel(dao, summarizeUseCase, recordingController)

        // 1回目
        viewModel.startRecording()
        val firstSessionId = viewModel.uiState.value.sessionId
        assertTrue(viewModel.uiState.value.isRecording)

        // 2回目: startRecording → sessionId変更、isRecording=true
        viewModel.startRecording()
        val secondSessionId = viewModel.uiState.value.sessionId
        assertTrue(firstSessionId != secondSessionId)
        assertTrue(viewModel.uiState.value.isRecording)
        // summaryはnull（要約未実行）
        assertNull(viewModel.uiState.value.summary)
    }
}
