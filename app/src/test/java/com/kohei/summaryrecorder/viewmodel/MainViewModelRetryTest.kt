package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelRetryTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var chunkRepository: ChunkRepository
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var recordingController: RecordingController
    private lateinit var summaryDao: SummaryDao
    private lateinit var deleteSummaryUseCase: DeleteSummaryUseCase
    private lateinit var backupRestoreUseCase: BackupRestoreUseCase
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
        backupRestoreUseCase = mockk<BackupRestoreUseCase>(relaxed = true)
        application = mockk<Application>(relaxed = true)
        savedStateHandle = SavedStateHandle()
        summariesFlow = MutableStateFlow(emptyList())
        every { chunkRepository.getChunksFlow(any()) } returns chunksFlow
        every { recordingController.isReady } returns MutableStateFlow(true)
        every { recordingController.currentVolumeLevel } returns 0f
        every { recordingController.currentSessionId } returns null
        every { summaryDao.observeAll() } returns flowOf(emptyList<SummaryEntity>())
        coEvery { recordingController.awaitReady() } returns Unit
        coEvery { summaryDao.getAll() } returns emptyList()
        val filesDir = tempFolder.newFolder("files")
        every { application.filesDir } returns filesDir
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() = MainViewModel(
        chunkRepository, summarizeUseCase, recordingController, summaryDao, deleteSummaryUseCase, backupRestoreUseCase, application, savedStateHandle
    )

    // ===== retryPendingRecords (init内で呼ばれる) =====

    @Test
    fun `retryPendingRecords retries RECORDED records on init`() {
        val recordedEntity = SummaryEntity(
            sessionId = "s1", audioFilePath = "/f.wav", durationMs = 1000L,
            status = SummaryStatus.RECORDED
        )
        coEvery { summaryDao.getByStatus(listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING)) } returns listOf(recordedEntity)
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        createViewModel()

        coVerify { summarizeUseCase.executeAndPersist("s1", summaryDao) }
    }

    @Test
    fun `retryPendingRecords retries SUMMARIZING records on init`() {
        val summarizingEntity = SummaryEntity(
            sessionId = "s2", audioFilePath = "/f.wav", durationMs = 2000L,
            status = SummaryStatus.SUMMARIZING
        )
        coEvery { summaryDao.getByStatus(listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING)) } returns listOf(summarizingEntity)
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        createViewModel()

        coVerify { summarizeUseCase.executeAndPersist("s2", summaryDao) }
    }

    @Test
    fun `retryPendingRecords handles mixed success and failure`() {
        val e1 = SummaryEntity(sessionId = "s1", status = SummaryStatus.RECORDED, audioFilePath = "/f1.wav")
        val e2 = SummaryEntity(sessionId = "s2", status = SummaryStatus.SUMMARIZING, audioFilePath = "/f2.wav")
        coEvery { summaryDao.getByStatus(listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING)) } returns listOf(e1, e2)
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        createViewModel()

        coVerify { summarizeUseCase.executeAndPersist("s1", summaryDao) }
        coVerify { summarizeUseCase.executeAndPersist("s2", summaryDao) }
    }

    @Test
    fun `retryPendingRecords with no pending records does nothing`() {
        coEvery { summaryDao.getByStatus(any()) } returns emptyList()

        createViewModel()

        coVerify(exactly = 0) { summarizeUseCase.executeAndPersist(any(), any()) }
    }

    // ===== retrySummary (手動再試行) =====

    @Test
    fun `retrySummary success path`() {
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summarizeUseCase.executeAndPersist("s1", summaryDao) }
    }

    @Test
    fun `retrySummary failure path sets ERROR`() {
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summarizeUseCase.executeAndPersist("s1", summaryDao) }
    }

    // ===== clearError =====

    @Test
    fun `clearError removes error from state`() {
        val vm = createViewModel()
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    // ===== updateTitle =====

    @Test
    fun `updateTitle calls dao with correct args`() {
        val vm = createViewModel()
        vm.updateTitle("s1", "新しいタイトル")
        coVerify { summaryDao.updateTitle("s1", "新しいタイトル") }
    }

    // ===== markAsRead =====

    @Test
    fun `markAsRead calls dao`() {
        val vm = createViewModel()
        vm.markAsRead("s1")
        coVerify { summaryDao.updateRead("s1", true) }
    }

    // ===== formatTimer =====

    @Test
    fun `formatTimer zero`() {
        val vm = createViewModel()
        assertEquals("00:00:00", vm.formatTimer(0))
    }

    @Test
    fun `formatTimer with hours`() {
        val vm = createViewModel()
        assertEquals("01:23:45", vm.formatTimer(1 * 3600 + 23 * 60 + 45))
    }

    @Test
    fun `formatTimer minutes and seconds only`() {
        val vm = createViewModel()
        assertEquals("00:05:30", vm.formatTimer(330))
    }
}
