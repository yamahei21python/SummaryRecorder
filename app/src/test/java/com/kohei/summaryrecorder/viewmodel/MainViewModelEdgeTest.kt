package com.kohei.summaryrecorder.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import com.kohei.summaryrecorder.service.ServiceRecordingController
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelEdgeTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var chunkRepo: ChunkRepository
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var controller: ServiceRecordingController
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        chunkRepo = mockk(relaxed = true)
        summarizeUseCase = mockk(relaxed = true)
        controller = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
        chunksFlow = MutableStateFlow(emptyList())

        every { chunkRepo.getChunksFlow(any()) } returns chunksFlow
        every { chunkRepo.observeBySession(any()) } returns chunksFlow

        viewModel = MainViewModel(chunkRepo, summarizeUseCase, controller, savedStateHandle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `T4 - stopRecording with no chunks does not leave isLoading forever`() {
        viewModel.startRecording()
        assertTrue(viewModel.uiState.value.isRecording)

        viewModel.stopRecording()

        assertFalse(viewModel.uiState.value.isRecording)
        assertFalse(viewModel.uiState.value.isLoading, "チャンクがない場合はスピナーが解除されること")
    }

    @Test
    fun `T13 - double startRecording stops previous session and starts new one`() {
        viewModel.startRecording()
        val firstSessionId = viewModel.uiState.value.sessionId

        viewModel.startRecording()
        val secondSessionId = viewModel.uiState.value.sessionId

        assertNotEquals(firstSessionId, secondSessionId, "セッションIDが変わっていること")
        verify(atLeast = 1) { controller.stopRecording() }
        verify(exactly = 2) { controller.startRecording(any()) }
    }

    // B4: isLoading チラつきテスト
    @Test
    fun `stopRecording no chunks - isLoading becomes false immediately`() {
        viewModel.startRecording()
        // チャンク0件のまま停止
        viewModel.stopRecording()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `stopRecording with chunks - isLoading stays true until allDone`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約")

        viewModel.startRecording()

        val doneChunks = listOf(
            ChunkEntity(id = 1, sessionId = viewModel.uiState.value.sessionId,
                chunkIndex = 0, filePath = "/c0.wav", status = ChunkStatus.DONE, transcriptionText = "t", isLast = true)
        )
        chunksFlow.value = doneChunks
        advanceUntilIdle()

        // チャンクありで停止
        viewModel.stopRecording()
        assertTrue(viewModel.uiState.value.isLoading, "チャンクありなら isLoading=true")

        // 再emitで要約完了
        chunksFlow.value = doneChunks.map { it.copy(updatedAt = it.updatedAt + 1) }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading, "allDone後は isLoading=false")
        assertEquals("要約", viewModel.uiState.value.summary)
    }

    // R7: SavedStateHandle テスト
    @Test
    fun `summarized persists across process death`() = runTest {
        // SavedStateHandle に summarized=true を保存済の状態でVM再生成
        savedStateHandle["summarized"] = true
        savedStateHandle["session_id"] = "existing-session"

        every { chunkRepo.getChunksFlow("existing-session") } returns chunksFlow

        // 既にsummarized=trueのVM
        val restoredViewModel = MainViewModel(chunkRepo, summarizeUseCase, controller, savedStateHandle)

        // allDoneチャンクを流す
        val doneChunks = listOf(
            ChunkEntity(id = 1, sessionId = "existing-session",
                chunkIndex = 0, filePath = "/c0.wav", status = ChunkStatus.DONE, transcriptionText = "t")
        )
        chunksFlow.value = doneChunks
        advanceUntilIdle()

        // startRecordingしない限り observeChunks が呼ばれないのでsummarizeも呼ばれない
        coVerify(exactly = 0) { summarizeUseCase.execute(any()) }
    }

    @Test
    fun `new session resets summarized`() {
        savedStateHandle["summarized"] = true

        viewModel.startRecording()

        // startRecording で summarized が false にリセットされる
        assertFalse(savedStateHandle["summarized"] ?: true)
    }
}
