package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.DeleteSummaryUseCase
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTabSessionTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var chunkRepository: ChunkRepository
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var recordingController: RecordingController
    private lateinit var summaryDao: SummaryDao
    private lateinit var deleteSummaryUseCase: DeleteSummaryUseCase
    private lateinit var application: Application
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var summariesFlow: MutableStateFlow<List<SummaryEntity>>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        chunkRepository = mockk<ChunkRepository>(relaxed = true)
        summarizeUseCase = mockk<SummarizeUseCase>()
        chunksFlow = MutableStateFlow(emptyList())
        recordingController = mockk<RecordingController>(relaxed = true)
        summaryDao = mockk<SummaryDao>(relaxed = true)
        deleteSummaryUseCase = mockk<DeleteSummaryUseCase>(relaxed = true)
        application = mockk<Application>(relaxed = true)
        savedStateHandle = SavedStateHandle()
        summariesFlow = MutableStateFlow(emptyList())
        every { chunkRepository.getChunksFlow(any()) } returns chunksFlow
        every { recordingController.isReady } returns MutableStateFlow(true)
        every { recordingController.currentVolumeLevel } returns 0f
        every { summaryDao.observeAll() } returns summariesFlow
        coEvery { recordingController.awaitReady() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() = MainViewModel(
        chunkRepository, summarizeUseCase, recordingController, summaryDao, deleteSummaryUseCase, application, savedStateHandle
    )

    @Test
    fun `onTabSelected updates selectedTab`() {
        val vm = createViewModel()
        assertEquals(0, vm.uiState.value.selectedTab)
        vm.onTabSelected(1)
        assertEquals(1, vm.uiState.value.selectedTab)
    }

    @Test
    fun `onTabSelected tab 2 sets selectedTab to 2`() {
        val vm = createViewModel()
        vm.onTabSelected(2)
        assertEquals(2, vm.uiState.value.selectedTab)
    }

    @Test
    fun `pauseRecording sets isPaused true`() {
        val vm = createViewModel()
        vm.startRecording()
        vm.pauseRecording()
        assertTrue(vm.uiState.value.isPaused)
        coVerify { recordingController.pauseRecording() }
    }

    @Test
    fun `resumeRecording sets isPaused false`() {
        val vm = createViewModel()
        vm.startRecording()
        vm.pauseRecording()
        vm.resumeRecording()
        assertFalse(vm.uiState.value.isPaused)
        coVerify { recordingController.resumeRecording() }
    }

    @Test
    fun `deleteSummary calls useCase`() {
        val vm = createViewModel()
        vm.deleteSummary("session1", "/path/to/file.wav")
        coVerify { deleteSummaryUseCase.execute("session1", "/path/to/file.wav") }
    }

    @Test
    fun `retrySummary updates status and calls summarize`() {
        coEvery { summaryDao.getBySessionId("s1") } returns SummaryEntity(
            sessionId = "s1", audioFilePath = "/f.wav", durationMs = 1000L,
            status = SummaryStatus.ERROR, errorMessage = "prev error"
        )
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summarizeUseCase.executeAndPersist("s1", summaryDao) }
    }

    @Test
    fun `markAsRead calls dao`() {
        val vm = createViewModel()
        vm.markAsRead("s1")
        coVerify { summaryDao.updateRead("s1", true) }
    }

    @Test
    fun `updateTitle calls dao`() {
        val vm = createViewModel()
        vm.updateTitle("s1", "新タイトル")
        coVerify { summaryDao.updateTitle("s1", "新タイトル") }
    }

    @Test
    fun `formatDuration formats correctly`() {
        val vm = createViewModel()
        assertEquals("1:30", vm.formatDuration(90000L))
        assertEquals("0:00", vm.formatDuration(0L))
        assertEquals("1:00:00", vm.formatDuration(3600000L))
    }

    @Test
    fun `formatTimer formats correctly`() {
        val vm = createViewModel()
        assertEquals("00:00:00", vm.formatTimer(0))
        assertEquals("00:01:30", vm.formatTimer(90))
        assertEquals("01:00:00", vm.formatTimer(3600))
    }

    @Test
    fun `summaries flow updates UiState`() {
        val entities = listOf(
            SummaryEntity(sessionId = "s1", title = "Test", status = SummaryStatus.DONE)
        )
        summariesFlow.value = entities

        val vm = createViewModel()
        assertEquals(entities, vm.uiState.value.summaries)
    }

    @Test
    fun `unread badge count updates from summaries flow`() {
        val entities = listOf(
            SummaryEntity(sessionId = "s1", status = SummaryStatus.DONE, isRead = false),
            SummaryEntity(sessionId = "s2", status = SummaryStatus.DONE, isRead = true),
            SummaryEntity(sessionId = "s3", status = SummaryStatus.ERROR)
        )
        summariesFlow.value = entities

        val vm = createViewModel()
        assertEquals(1, vm.uiState.value.unreadBadgeCount)
    }
}
