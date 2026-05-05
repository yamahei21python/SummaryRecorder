package com.kohei.summaryrecorder.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import com.kohei.summaryrecorder.service.ServiceRecordingController
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelEdgeTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var chunkRepo: ChunkRepository
    private lateinit var controller: ServiceRecordingController
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        chunkRepo = mockk(relaxed = true)
        controller = mockk(relaxed = true)
        
        every { chunkRepo.getChunksFlow(any()) } returns flowOf(emptyList())
        
        viewModel = MainViewModel(chunkRepo, controller)
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
        
        // 録音停止
        viewModel.stopRecording()
        
        assertFalse(viewModel.uiState.value.isRecording)
        assertFalse(viewModel.uiState.value.isLoading, "チャンクがない場合はスピナーが解除されること")
    }

    @Test
    fun `T13 - double startRecording stops previous session and starts new one`() {
        viewModel.startRecording()
        val firstSessionId = viewModel.uiState.value.sessionId
        
        // 即座に2回目を呼ぶ
        viewModel.startRecording()
        val secondSessionId = viewModel.uiState.value.sessionId
        
        assertNotEquals(firstSessionId, secondSessionId, "セッションIDが変わっていること")
        // controller.stopRecording() が2回目の開始時に呼ばれていることを確認
        verify(atLeast = 1) { controller.stopRecording() }
        verify(exactly = 2) { controller.startRecording(any()) }
    }
}
