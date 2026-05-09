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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryFlowTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var transcriptionProvider: TranscriptionProvider
    private lateinit var summaryProvider: SummaryProvider
    private lateinit var recordingController: RecordingController
    private lateinit var summaryDao: SummaryDao
    private lateinit var deleteSummaryUseCase: DeleteSummaryUseCase
    private lateinit var application: Application
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var tempDir: File

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
        tempDir = File(System.getProperty("java.io.tmpdir"), "sft-${System.currentTimeMillis()}").also { it.mkdirs() }
        every { recordingController.startRecording(any()) } returns Unit
        every { recordingController.stopRecording() } returns Unit
        every { recordingController.isReady } returns MutableStateFlow(true)
        every { recordingController.currentVolumeLevel } returns 0f
        every { recordingController.currentSessionId } returns null
        every { summaryDao.observeAll() } returns flowOf(emptyList<SummaryEntity>())
        coEvery { recordingController.awaitReady() } returns Unit
        coEvery { summaryDao.getByStatus(any()) } returns emptyList()
        coEvery { summaryDao.getAll() } returns emptyList()
        every { application.filesDir } returns tempDir
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    private fun createViewModel() = MainViewModel(
        transcriptionProvider, summaryProvider, recordingController, summaryDao,
        deleteSummaryUseCase, application, savedStateHandle
    )

    @Test
    fun `retrySummary success updates dao`() {
        val wavFile = File(tempDir, "test.wav").also { it.writeText("dummy") }
        coEvery { summaryDao.getBySessionId("s1") } returns SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, durationMs = 1000L,
            status = SummaryStatus.ERROR, errorMessage = "prev error"
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
    fun `retrySummary transcription failure sets ERROR status`() {
        val wavFile = File(tempDir, "test.wav").also { it.writeText("dummy") }
        coEvery { summaryDao.getBySessionId("s1") } returns SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, durationMs = 1000L,
            status = SummaryStatus.ERROR
        )
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.failure(RuntimeException("transcription failed"))

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = "transcription failed") }
    }

    @Test
    fun `retrySummary summarize failure sets ERROR status`() {
        val wavFile = File(tempDir, "test.wav").also { it.writeText("dummy") }
        coEvery { summaryDao.getBySessionId("s1") } returns SummaryEntity(
            sessionId = "s1", audioFilePath = wavFile.absolutePath, durationMs = 1000L,
            status = SummaryStatus.ERROR
        )
        coEvery { transcriptionProvider.transcribe(any()) } returns Result.success("text")
        coEvery { summaryProvider.summarize("text") } returns Result.failure(RuntimeException("summarize failed"))

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summaryDao.updateStatus("s1", SummaryStatus.SUMMARIZING) }
        coVerify { summaryDao.updateStatus("s1", SummaryStatus.ERROR, errorMessage = "summarize failed") }
    }

    @Test
    fun `deleteSummary calls useCase`() {
        val vm = createViewModel()
        vm.deleteSummary("s1", "/path/file.wav")

        coVerify { deleteSummaryUseCase.execute("s1", "/path/file.wav") }
    }
}
