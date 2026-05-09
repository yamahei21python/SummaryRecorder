package com.kohei.summaryrecorder.viewmodel

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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSummarizeOnceTest {

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
        every { recordingController.startRecording(any()) } returns Unit
        every { recordingController.isReady } returns MutableStateFlow(true)
        every { recordingController.currentVolumeLevel } returns 0f
        every { recordingController.currentSessionId } returns null
        every { summaryDao.observeAll() } returns flowOf(emptyList<SummaryEntity>())
        coEvery { recordingController.awaitReady() } returns Unit
        coEvery { summaryDao.getByStatus(any()) } returns emptyList()
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

    @Test
    fun `retrySummary calls transcription and summary providers and updates dao`() {
        val filesDir = tempFolder.newFolder("retry")
        val wavFile = File(filesDir, "f.wav").also { it.writeText("dummy") }
        coEvery { summaryDao.getBySessionId("s1") } returns SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, status = SummaryStatus.ERROR
        )
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.success("transcribed text")
        coEvery { summaryProvider.summarize("transcribed text") } returns Result.success(
            SummaryResult(title = "title", summaryText = "summary")
        )

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { transcriptionProvider.transcribe(any()) }
        coVerify { summaryProvider.summarize("transcribed text") }
        coVerify { summaryDao.updateStatusAndContent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retrySummary on transcription failure sets ERROR status`() {
        val filesDir = tempFolder.newFolder("retry2")
        val wavFile = File(filesDir, "f.wav").also { it.writeText("dummy") }
        coEvery { summaryDao.getBySessionId("s1") } returns SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, status = SummaryStatus.ERROR
        )
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.failure(RuntimeException("fail"))

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = "fail") }
    }
}
