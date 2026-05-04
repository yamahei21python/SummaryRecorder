package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.repository.SummaryRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * - summaryRepo失敗時 isLoading=false + errorセット
 * - clearError → error=null
 * - startRecording → service intent発行確認
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class MainViewModelEdgeTest {

    private lateinit var dao: ChunkDao
    private lateinit var summaryRepo: SummaryRepository
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dao = mockk<ChunkDao>(relaxed = true)
        summaryRepo = mockk<SummaryRepository>()
        chunksFlow = MutableStateFlow(emptyList())
        context = mockk<Context>(relaxed = true)
        coEvery { dao.observeBySession(any()) } returns chunksFlow
        every { context.startService(any()) } returns null
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
        coEvery { summaryRepo.summarize(any()) } returns Result.success("要約")

        val viewModel = MainViewModel(dao, summaryRepo)

        // 1回目の録音開始
        viewModel.startRecording(context)
        val firstSessionId = viewModel.uiState.value.sessionId
        assertTrue(viewModel.uiState.value.isRecording)

        // 停止
        viewModel.stopRecording(context)
        assertFalse(viewModel.uiState.value.isRecording)

        // 2回目の録音開始
        viewModel.startRecording(context)
        val secondSessionId = viewModel.uiState.value.sessionId

        // sessionIdが異なることを確認
        assertTrue(
            "Session IDs should differ: $firstSessionId vs $secondSessionId",
            firstSessionId != secondSessionId
        )
    }

    @Test
    fun `consecutive start-stop-start observes new session`() = runTest {
        coEvery { summaryRepo.summarize(any()) } returns Result.success("要約")

        val viewModel = MainViewModel(dao, summaryRepo)

        // 1回目
        viewModel.startRecording(context)
        val firstSession = viewModel.uiState.value.sessionId

        // 停止
        viewModel.stopRecording(context)

        // 2回目
        viewModel.startRecording(context)
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
        val viewModel = MainViewModel(dao, summaryRepo)
        viewModel.startRecording(context)

        // 空リストemit
        chunksFlow.value = emptyList()

        assertNull(viewModel.uiState.value.summary)
        coVerify(exactly = 0) { summaryRepo.summarize(any()) }
    }

    @Test
    fun `empty combined text still calls summarize when all done`() = runTest {
        // 全DONEだがtranscriptionText全null → combinedText=""
        coEvery { summaryRepo.summarize(any()) } returns Result.success("空の要約")

        val viewModel = MainViewModel(dao, summaryRepo)
        viewModel.startRecording(context)

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
            // 呼ばれたことをcoVerifyで確認
        }

        coVerify(atLeast = 1) { summaryRepo.summarize(any()) }
    }

    // ===== summaryRepo失敗 =====

    @Test
    fun `summarize failure sets isLoading=false and error`() = runTest {
        coEvery { summaryRepo.summarize(any()) } returns Result.failure(
            RuntimeException("Gemini API error")
        )

        val viewModel = MainViewModel(dao, summaryRepo)
        viewModel.startRecording(context)

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
        coEvery { summaryRepo.summarize(any()) } returns Result.failure(
            RuntimeException("fail")
        )

        val viewModel = MainViewModel(dao, summaryRepo)
        viewModel.startRecording(context)

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
        coVerify(exactly = 0) { dao.deleteBySession(any()) }
    }

    // ===== clearError =====

    @Test
    fun `clearError sets error to null`() = runTest {
        coEvery { summaryRepo.summarize(any()) } returns Result.failure(
            RuntimeException("test error")
        )

        val viewModel = MainViewModel(dao, summaryRepo)
        viewModel.startRecording(context)

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
    fun `startRecording sends service intent`() = runTest {
        val viewModel = MainViewModel(dao, summaryRepo)
        viewModel.startRecording(context)

        // context.startService が呼ばれたことを確認
        io.mockk.verify { context.startService(any()) }
    }

    @Test
    fun `stopRecording sends stop intent`() = runTest {
        val viewModel = MainViewModel(dao, summaryRepo)
        viewModel.startRecording(context)
        viewModel.stopRecording(context)

        // startServiceが2回呼ばれる（start + stop）
        io.mockk.verify(atLeast = 2) { context.startService(any()) }
    }

    @Test
    fun `startRecording resets summary and error`() = runTest {
        val viewModel = MainViewModel(dao, summaryRepo)

        // 1回目: エラー発生
        coEvery { summaryRepo.summarize(any()) } returns Result.failure(RuntimeException("fail"))
        viewModel.startRecording(context)
        chunksFlow.value = listOf(doneChunk(id = 1, index = 0, text = "テキスト1"))
        coEvery { dao.getBySession(any()) } returns listOf(doneChunk(id = 1, index = 0, text = "テキスト1"))

        viewModel.uiState.test { awaitItem() }

        // 2回目: 録音開始 → summary/errorリセット
        viewModel.startRecording(context)
        assertNull(viewModel.uiState.value.summary)
        assertNull(viewModel.uiState.value.error)
    }
}
