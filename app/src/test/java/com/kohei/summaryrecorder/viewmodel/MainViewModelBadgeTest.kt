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
class MainViewModelBadgeTest {

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
        every { recordingController.currentSessionId } returns null
        every { summaryDao.observeAll() } returns summariesFlow
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
        chunkRepository, summarizeUseCase, recordingController, summaryDao,
        deleteSummaryUseCase, application, savedStateHandle
    )

    // ===== unreadBadgeCount computation =====

    @Test
    fun `badge count is zero when no summaries`() {
        val vm = createViewModel()
        assertEquals(0, vm.uiState.value.unreadBadgeCount)
    }

    @Test
    fun `badge count increments for DONE unread summary`() {
        val entity = SummaryEntity(
            sessionId = "s1", status = SummaryStatus.DONE, isRead = false,
            audioFilePath = "/f.wav", durationMs = 1000L
        )
        summariesFlow.value = listOf(entity)

        val vm = createViewModel()
        assertEquals(1, vm.uiState.value.unreadBadgeCount)
    }

    @Test
    fun `badge count excludes read summaries`() {
        val unread = SummaryEntity(
            sessionId = "s1", status = SummaryStatus.DONE, isRead = false,
            audioFilePath = "/f.wav", durationMs = 1000L
        )
        val read = SummaryEntity(
            sessionId = "s2", status = SummaryStatus.DONE, isRead = true,
            audioFilePath = "/f.wav", durationMs = 1000L
        )
        summariesFlow.value = listOf(unread, read)

        val vm = createViewModel()
        assertEquals(1, vm.uiState.value.unreadBadgeCount)
    }

    @Test
    fun `badge count excludes non-DONE summaries`() {
        val recorded = SummaryEntity(
            sessionId = "s1", status = SummaryStatus.RECORDED, isRead = false,
            audioFilePath = "/f.wav", durationMs = 1000L
        )
        val error = SummaryEntity(
            sessionId = "s2", status = SummaryStatus.ERROR, isRead = false,
            audioFilePath = "/f.wav", durationMs = 1000L
        )
        val summarizing = SummaryEntity(
            sessionId = "s3", status = SummaryStatus.SUMMARIZING, isRead = false,
            audioFilePath = "/f.wav", durationMs = 1000L
        )
        summariesFlow.value = listOf(recorded, error, summarizing)

        val vm = createViewModel()
        assertEquals(0, vm.uiState.value.unreadBadgeCount)
    }

    @Test
    fun `badge count updates when summary marked as read`() {
        val entity = SummaryEntity(
            sessionId = "s1", status = SummaryStatus.DONE, isRead = false,
            audioFilePath = "/f.wav", durationMs = 1000L
        )
        summariesFlow.value = listOf(entity)

        val vm = createViewModel()
        assertEquals(1, vm.uiState.value.unreadBadgeCount)

        // markAsRead → DAO更新 → Flowが再通知
        val readEntity = entity.copy(isRead = true)
        summariesFlow.value = listOf(readEntity)

        assertEquals(0, vm.uiState.value.unreadBadgeCount)
    }

    @Test
    fun `badge count with multiple DONE unread summaries`() {
        val entities = (1..5).map { i ->
            SummaryEntity(
                sessionId = "s$i", status = SummaryStatus.DONE, isRead = false,
                audioFilePath = "/f$i.wav", durationMs = 1000L
            )
        }
        summariesFlow.value = entities

        val vm = createViewModel()
        assertEquals(5, vm.uiState.value.unreadBadgeCount)
    }

    @Test
    fun `badge count updates reactively when new summary appears`() {
        summariesFlow.value = emptyList()
        val vm = createViewModel()
        assertEquals(0, vm.uiState.value.unreadBadgeCount)

        // 新しいDONE未読summary追加
        summariesFlow.value = listOf(
            SummaryEntity(
                sessionId = "s1", status = SummaryStatus.DONE, isRead = false,
                audioFilePath = "/f.wav", durationMs = 1000L
            )
        )

        assertEquals(1, vm.uiState.value.unreadBadgeCount)
    }

    @Test
    fun `summaries list is also updated in uiState`() {
        val entities = listOf(
            SummaryEntity(
                sessionId = "s1", status = SummaryStatus.DONE, isRead = false,
                audioFilePath = "/f.wav", durationMs = 1000L
            )
        )
        summariesFlow.value = entities

        val vm = createViewModel()
        assertEquals(1, vm.uiState.value.summaries.size)
        assertEquals("s1", vm.uiState.value.summaries[0].sessionId)
    }
}
