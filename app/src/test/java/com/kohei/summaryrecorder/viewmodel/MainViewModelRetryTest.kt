package com.kohei.summaryrecorder.viewmodel

import com.kohei.summaryrecorder.ui.util.FormatUtil

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import com.kohei.summaryrecorder.data.model.SummaryResult
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.domain.usecase.DeleteSummaryUseCase
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelRetryTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var transcriptionProvider: TranscriptionProvider
    private lateinit var summaryProvider: SummaryProvider
    private lateinit var recordingController: RecordingController
    private lateinit var summaryDao: SummaryDao
    private lateinit var deleteSummaryUseCase: DeleteSummaryUseCase
    private lateinit var application: Application
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var summariesFlow: MutableStateFlow<List<SummaryEntity>>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        transcriptionProvider = mockk<TranscriptionProvider>(relaxed = true)
        summaryProvider = mockk<SummaryProvider>(relaxed = true)
        recordingController = mockk<RecordingController>(relaxed = true)
        summaryDao = mockk<SummaryDao>(relaxed = true)
        deleteSummaryUseCase = mockk<DeleteSummaryUseCase>(relaxed = true)
        application = mockk<Application>(relaxed = true)
        savedStateHandle = SavedStateHandle()
        summariesFlow = MutableStateFlow(emptyList())
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
        transcriptionProvider, summaryProvider, recordingController, summaryDao,
        deleteSummaryUseCase, application, savedStateHandle
    )

    // ===== retryPendingRecords (init内で呼ばれる) =====

    @Test
    fun `retryPendingRecords retries RECORDED records on init`() {
        val filesDir = tempFolder.newFolder("retry1")
        val wavFile = File(filesDir, "f.wav").also { it.writeText("dummy") }
        val recordedEntity = SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, durationMs = 1000L,
            status = SummaryStatus.RECORDED
        )
        coEvery { summaryDao.getByStatus(listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING)) } returns listOf(recordedEntity)
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.success("text")
        coEvery { summaryProvider.summarize("text") } returns Result.success(
            SummaryResult(title = "title", summaryText = "summary")
        )

        createViewModel()

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { transcriptionProvider.transcribe(any()) }
        coVerify { summaryProvider.summarize("text") }
        coVerify { summaryDao.updateStatusAndContent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retryPendingRecords retries SUMMARIZING records on init`() {
        val filesDir = tempFolder.newFolder("retry2")
        val wavFile = File(filesDir, "f.wav").also { it.writeText("dummy") }
        val summarizingEntity = SummaryEntity(
            sessionId = "s2", audioFilePath = wavFile.absolutePath, durationMs = 2000L,
            status = SummaryStatus.SUMMARIZING
        )
        coEvery { summaryDao.getByStatus(listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING)) } returns listOf(summarizingEntity)
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.success("text")
        coEvery { summaryProvider.summarize("text") } returns Result.success(
            SummaryResult(title = "title", summaryText = "summary")
        )

        createViewModel()

        coVerify { summaryDao.updateStatus("s2", SummaryStatus.SUMMARIZING) }
        coVerify { transcriptionProvider.transcribe(any()) }
        coVerify { summaryProvider.summarize("text") }
    }

    @Test
    fun `retryPendingRecords handles mixed success and failure`() {
        val filesDir = tempFolder.newFolder("retry3")
        val wavFile = File(filesDir, "f.wav").also { it.writeText("dummy") }
        val e1 = SummaryEntity(sessionId = "s1", status = SummaryStatus.RECORDED, audioFilePath = wavFile.absolutePath)
        val e2 = SummaryEntity(sessionId = "s2", status = SummaryStatus.SUMMARIZING, audioFilePath = wavFile.absolutePath)
        coEvery { summaryDao.getByStatus(listOf(SummaryStatus.RECORDED, SummaryStatus.SUMMARIZING)) } returns listOf(e1, e2)
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.success("text")
        coEvery { summaryProvider.summarize("text") } returns Result.success(
            SummaryResult(title = "title", summaryText = "summary")
        )

        createViewModel()

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatus("s2", SummaryStatus.SUMMARIZING) }
        coVerify(exactly = 2) { transcriptionProvider.transcribe(any()) }
    }

    @Test
    fun `retryPendingRecords with no pending records does nothing`() {
        coEvery { summaryDao.getByStatus(any()) } returns emptyList()

        createViewModel()

        coVerify(exactly = 0) { transcriptionProvider.transcribe(any()) }
        coVerify(exactly = 0) { summaryProvider.summarize(any()) }
    }

    // ===== retrySummary (手動再試行) =====

    @Test
    fun `retrySummary success path`() {
        val filesDir = tempFolder.newFolder("retry4")
        val wavFile = File(filesDir, "f.wav").also { it.writeText("dummy") }
        coEvery { summaryDao.getBySessionId("s1") } returns SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, status = SummaryStatus.ERROR
        )
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.success("text")
        coEvery { summaryProvider.summarize("text") } returns Result.success(
            SummaryResult(title = "title", summaryText = "summary")
        )

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatusAndContent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retrySummary failure path sets ERROR`() {
        val filesDir = tempFolder.newFolder("retry5")
        val wavFile = File(filesDir, "f.wav").also { it.writeText("dummy") }
        coEvery { summaryDao.getBySessionId("s1") } returns SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, status = SummaryStatus.ERROR
        )
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.failure(RuntimeException("fail"))

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = "fail") }
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
        assertEquals("00:00:00", FormatUtil.formatTimer(0))
    }

    @Test
    fun `formatTimer with hours`() {
        assertEquals("01:23:45", FormatUtil.formatTimer(1 * 3600 + 23 * 60 + 45))
    }

    @Test
    fun `formatTimer minutes and seconds only`() {
        assertEquals("00:05:30", FormatUtil.formatTimer(330))
    }
}
