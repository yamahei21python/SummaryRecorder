package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.BackupRestoreUseCase
import com.kohei.summaryrecorder.domain.usecase.DeleteSummaryUseCase
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelEdgeTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var chunkRepo: ChunkRepository
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var controller: RecordingController
    private lateinit var summaryDao: SummaryDao
    private lateinit var deleteSummaryUseCase: DeleteSummaryUseCase
    private lateinit var backupRestoreUseCase: BackupRestoreUseCase
    private lateinit var application: Application
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        chunkRepo = mockk(relaxed = true)
        summarizeUseCase = mockk(relaxed = true)
        controller = mockk<RecordingController>(relaxed = true)
        summaryDao = mockk(relaxed = true)
        deleteSummaryUseCase = mockk(relaxed = true)
        backupRestoreUseCase = mockk(relaxed = true)
        application = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
        chunksFlow = MutableStateFlow(emptyList())

        every { chunkRepo.getChunksFlow(any()) } returns chunksFlow
        every { chunkRepo.observeBySession(any()) } returns chunksFlow
        every { summaryDao.observeAll() } returns flowOf(emptyList<SummaryEntity>())
        every { controller.isReady } returns MutableStateFlow(true)
        every { controller.currentVolumeLevel } returns 0f
        coEvery { controller.awaitReady() } returns Unit

        viewModel = MainViewModel(chunkRepo, summarizeUseCase, controller, summaryDao, deleteSummaryUseCase, backupRestoreUseCase, application, savedStateHandle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `stopRecording with no chunks does not leave isLoading forever`() {
        viewModel.startRecording()
        assertTrue(viewModel.uiState.value.isRecording)

        viewModel.stopRecording()

        assertFalse(viewModel.uiState.value.isRecording)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `double startRecording starts new session`() {
        viewModel.startRecording()
        val firstSessionId = viewModel.uiState.value.sessionId

        viewModel.startRecording()
        val secondSessionId = viewModel.uiState.value.sessionId

        assertNotEquals(firstSessionId, secondSessionId)
        verify(exactly = 2) { controller.startRecording(any()) }
    }

    @Test
    fun `stopRecording no chunks - isLoading becomes false immediately`() {
        viewModel.startRecording()
        viewModel.stopRecording()
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `pauseRecording sets isPaused true`() {
        viewModel.startRecording()
        assertFalse(viewModel.uiState.value.isPaused)

        viewModel.pauseRecording()
        assertTrue(viewModel.uiState.value.isPaused)
    }

    @Test
    fun `resumeRecording sets isPaused false`() {
        viewModel.startRecording()
        viewModel.pauseRecording()
        assertTrue(viewModel.uiState.value.isPaused)

        viewModel.resumeRecording()
        assertFalse(viewModel.uiState.value.isPaused)
    }

    @Test
    fun `initial UiState has correct defaults`() {
        val state = viewModel.uiState.value
        assertEquals(0, state.selectedTab)
        assertFalse(state.isRecording)
        assertFalse(state.isPaused)
        assertEquals("", state.sessionId)
        assertEquals(0, state.recordingSeconds)
    }
}
