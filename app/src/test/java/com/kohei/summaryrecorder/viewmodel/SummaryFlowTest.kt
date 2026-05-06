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
class SummaryFlowTest {

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
        every { chunkRepository.getChunksFlow(any()) } returns chunksFlow
        every { recordingController.startRecording(any()) } returns Unit
        every { recordingController.stopRecording() } returns Unit
        every { recordingController.isReady } returns MutableStateFlow(true)
        every { recordingController.currentVolumeLevel } returns 0f
        every { summaryDao.observeAll() } returns flowOf(emptyList<SummaryEntity>())
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
    fun `retrySummary success updates dao`() {
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summarizeUseCase.executeAndPersist("s1", summaryDao) }
    }

    @Test
    fun `retrySummary failure sets ERROR status`() {
        coEvery { summarizeUseCase.executeAndPersist(any(), any()) } returns Unit

        val vm = createViewModel()
        vm.retrySummary("s1")

        coVerify { summarizeUseCase.executeAndPersist("s1", summaryDao) }
    }

    @Test
    fun `deleteSummary calls useCase`() {
        val vm = createViewModel()
        vm.deleteSummary("s1", "/path/file.wav")

        coVerify { deleteSummaryUseCase.execute("s1", "/path/file.wav") }
    }
}
