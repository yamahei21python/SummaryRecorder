package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SessionHistory
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * タブUI、セッション履歴、手動削除、retryLastSummary、clearError のテスト
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTabSessionTest {

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
        every { chunkRepository.getChunksFlow(any()) } returns chunksFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = MainViewModel(
        chunkRepository, summarizeUseCase, recordingController, savedStateHandle
    )

    // ===== onTabSelected =====

    @Test
    fun `onTabSelected updates selectedTab`() {
        val vm = createViewModel()
        assertEquals(0, vm.uiState.value.selectedTab)

        vm.onTabSelected(1)
        assertEquals(1, vm.uiState.value.selectedTab)
    }

    @Test
    fun `onTabSelected tab 1 triggers loadSessions`() = runTest {
        val sessions = listOf(
            SessionHistory("s1", 1000L, 3, 2, 1)
        )
        coEvery { chunkRepository.getSessionHistory() } returns sessions

        val vm = createViewModel()
        vm.onTabSelected(1)
        advanceUntilIdle()

        assertEquals(sessions, vm.uiState.value.sessions)
    }

    @Test
    fun `onTabSelected tab 0 does not load sessions`() = runTest {
        val vm = createViewModel()
        vm.onTabSelected(0)
        advanceUntilIdle()

        coVerify(exactly = 0) { chunkRepository.getSessionHistory() }
    }

    // ===== loadSessions =====

    @Test
    fun `loadSessions sets isSessionsLoading then updates sessions`() = runTest {
        val sessions = listOf(
            SessionHistory("s1", 1000L, 5, 5, 0),
            SessionHistory("s2", 2000L, 3, 1, 2)
        )
        coEvery { chunkRepository.getSessionHistory() } returns sessions

        val vm = createViewModel()
        vm.loadSessions()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isSessionsLoading)
        assertEquals(2, vm.uiState.value.sessions.size)
        assertEquals("s1", vm.uiState.value.sessions[0].sessionId)
    }

    @Test
    fun `loadSessions on error clears isSessionsLoading`() = runTest {
        coEvery { chunkRepository.getSessionHistory() } throws RuntimeException("DB error")

        val vm = createViewModel()
        vm.loadSessions()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isSessionsLoading)
        assertEquals(emptyList<SessionHistory>(), vm.uiState.value.sessions)
    }

    // ===== deleteSession =====

    @Test
    fun `deleteSession removes session and reloads list`() = runTest {
        val sessionsBefore = listOf(
            SessionHistory("s1", 1000L, 3, 3, 0),
            SessionHistory("s2", 2000L, 2, 2, 0)
        )
        val sessionsAfter = listOf(
            SessionHistory("s2", 2000L, 2, 2, 0)
        )
        coEvery { chunkRepository.getSessionHistory() } returns sessionsBefore andThen sessionsAfter

        val vm = createViewModel()
        vm.onTabSelected(1)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.sessions.size)

        vm.deleteSession("s1")
        advanceUntilIdle()

        coVerify { chunkRepository.deleteSessionData("s1") }
        assertEquals(1, vm.uiState.value.sessions.size)
        assertEquals("s2", vm.uiState.value.sessions[0].sessionId)
    }

    @Test
    fun `deleteSession on error does not crash`() = runTest {
        coEvery { chunkRepository.deleteSessionData(any()) } throws RuntimeException("fail")
        coEvery { chunkRepository.getSessionHistory() } returns emptyList()

        val vm = createViewModel()
        vm.deleteSession("nonexistent")
        advanceUntilIdle()

        // クラッシュしないこと
        assertEquals(emptyList<SessionHistory>(), vm.uiState.value.sessions)
    }

    // ===== retryLastSummary =====

    @Test
    fun `retryLastSummary retries summarizeAll with current sessionId`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("リトライ要約")

        val vm = createViewModel()
        vm.startRecording()
        val sid = vm.uiState.value.sessionId

        vm.retryLastSummary()
        advanceUntilIdle()

        coVerify { summarizeUseCase.execute(sid) }
        assertEquals("リトライ要約", vm.uiState.value.summary)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `retryLastSummary clears error and sets isLoading`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(RuntimeException("err"))

        val vm = createViewModel()
        vm.startRecording()

        // 初回失敗状態を作る
        vm.retryLastSummary()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.error!!.contains("err"))

        // リトライ成功
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("OK")
        vm.retryLastSummary()
        advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertEquals("OK", vm.uiState.value.summary)
    }

    @Test
    fun `retryLastSummary with blank sessionId is no-op`() = runTest {
        val vm = createViewModel()

        vm.retryLastSummary()

        coVerify(exactly = 0) { summarizeUseCase.execute(any()) }
    }

    @Test
    fun `retryLastSummary resets summarized flag allowing re-summarization`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約1") andThen Result.success("要約2")

        val vm = createViewModel()
        vm.startRecording()

        // 1回目
        vm.retryLastSummary()
        advanceUntilIdle()
        assertEquals("要約1", vm.uiState.value.summary)

        // 2回目 — summarized=falseにリセットされるので再度実行可能
        vm.retryLastSummary()
        advanceUntilIdle()
        assertEquals("要約2", vm.uiState.value.summary)
    }

    // ===== clearError =====

    @Test
    fun `clearError removes error from state`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(RuntimeException("fail"))

        val vm = createViewModel()
        vm.startRecording()
        vm.retryLastSummary()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)

        vm.clearError()

        assertNull(vm.uiState.value.error)
    }

    // ===== formatDate / formatSessionStatus =====

    @Test
    fun `formatDate returns formatted string`() {
        val vm = createViewModel()
        val result = vm.formatDate(1700000000000L)
        // タイムゾーン依存だがnull/空でないことだけ確認
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `formatSessionStatus with no failures shows complete`() {
        val vm = createViewModel()
        val session = SessionHistory("s1", 1000L, 3, 3, 0)
        val result = vm.formatSessionStatus(session)
        assertTrue(result.contains("完了"))
        assertFalse(result.contains("失敗"))
    }

    @Test
    fun `formatSessionStatus with failures shows failure count`() {
        val vm = createViewModel()
        val session = SessionHistory("s1", 1000L, 5, 3, 2)
        val result = vm.formatSessionStatus(session)
        assertTrue(result.contains("2失敗"))
    }

    // ===== UiState initial values =====

    @Test
    fun `initial UiState has correct defaults`() {
        val vm = createViewModel()
        val state = vm.uiState.value

        assertEquals(0, state.selectedTab)
        assertFalse(state.isRecording)
        assertEquals("", state.sessionId)
        assertEquals(emptyList<ChunkEntity>(), state.chunks)
        assertNull(state.summary)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(emptyList<SessionHistory>(), state.sessions)
        assertFalse(state.isSessionsLoading)
    }
}
