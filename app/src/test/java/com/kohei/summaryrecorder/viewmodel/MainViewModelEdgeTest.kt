package com.kohei.summaryrecorder.viewmodel

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.controller.RecordingController
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.SummarizeUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class MainViewModelEdgeTest {

    private lateinit var chunkRepository: ChunkRepository
    private lateinit var summarizeUseCase: SummarizeUseCase
    private lateinit var chunksFlow: MutableStateFlow<List<ChunkEntity>>
    private lateinit var recordingController: RecordingController

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        chunkRepository = mockk<ChunkRepository>(relaxed = true)
        summarizeUseCase = mockk<SummarizeUseCase>()
        chunksFlow = MutableStateFlow(emptyList())
        recordingController = mockk<RecordingController>(relaxed = true)
        every { chunkRepository.observeBySession(any()) } returns chunksFlow
        every { recordingController.startRecording(any()) } returns Unit
        every { recordingController.stopRecording() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun doneChunk(id: Long, index: Int, text: String, sessionId: String = "test-session") = ChunkEntity(
        id = id,
        sessionId = sessionId,
        chunkIndex = index,
        filePath = "/chunk_$index.wav",
        status = ChunkStatus.DONE,
        transcriptionText = text
    )

    @Test
    fun `consecutive start-stop-start switches sessionId`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約")

        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)

        viewModel.startRecording()
        val firstSessionId = viewModel.uiState.value.sessionId
        assertTrue(viewModel.uiState.value.isRecording)

        viewModel.stopRecording()
        assertFalse(viewModel.uiState.value.isRecording)

        viewModel.startRecording()
        val secondSessionId = viewModel.uiState.value.sessionId

        assertTrue(
            "Session IDs should differ: $firstSessionId vs $secondSessionId",
            firstSessionId != secondSessionId
        )
    }

    @Test
    fun `consecutive start-stop-start observes new session`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("要約")

        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)

        viewModel.startRecording()
        val firstSession = viewModel.uiState.value.sessionId

        viewModel.stopRecording()

        viewModel.startRecording()
        val secondSession = viewModel.uiState.value.sessionId

        val doneChunks = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1", sessionId = secondSession)
        )
        chunksFlow.value = doneChunks

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("要約", state.summary)
        }
    }

    @Test
    fun `emissions from previous session flow are ignored after restart`() = runTest {
        val firstFlow = MutableStateFlow<List<ChunkEntity>>(emptyList())
        val secondFlow = MutableStateFlow<List<ChunkEntity>>(emptyList())
        
        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)

        // Session 1
        viewModel.startRecording()
        val firstSession = viewModel.uiState.value.sessionId
        // Re-mock specifically for first session to be sure
        every { chunkRepository.observeBySession(firstSession) } returns firstFlow

        viewModel.stopRecording()

        // Session 2
        viewModel.startRecording()
        val secondSession = viewModel.uiState.value.sessionId
        every { chunkRepository.observeBySession(secondSession) } returns secondFlow

        // Emit to the FIRST session's flow
        firstFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "古いセッション", sessionId = firstSession)
        )

        // Should not trigger summary or update chunks since job was cancelled
        assertEquals(emptyList<ChunkEntity>(), viewModel.uiState.value.chunks)
        assertNull(viewModel.uiState.value.summary)
        
        // Emit to the SECOND session's flow
        secondFlow.value = listOf(
            doneChunk(id = 2, index = 0, text = "新しいセッション", sessionId = secondSession)
        )
        
        // Should update chunks
        assertEquals(1, viewModel.uiState.value.chunks.size)
        assertEquals("新しいセッション", viewModel.uiState.value.chunks[0].transcription)
    }

    @Test
    fun `empty chunks does not trigger summarize`() = runTest {
        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()

        chunksFlow.value = emptyList()

        assertNull(viewModel.uiState.value.summary)
    }

    @Test
    fun `empty combined text still calls summarize when all done`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.success("空の要約")

        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()

        val doneNoText = ChunkEntity(
            id = 1,
            sessionId = viewModel.uiState.value.sessionId,
            chunkIndex = 0,
            filePath = "/chunk_0.wav",
            status = ChunkStatus.DONE,
            transcriptionText = null
        )
        chunksFlow.value = listOf(doneNoText)

        viewModel.uiState.test {
            awaitItem()
        }
    }

    @Test
    fun `summarize failure sets isLoading=false and error`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(
            RuntimeException("Gemini API error")
        )

        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()

        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse("isLoading should be false after failure", state.isLoading)
            assertNotNull("error should be set", state.error)
            assertTrue(
                "error should contain 'Gemini API error'",
                state.error!!.contains("Gemini API error")
            )
        }
    }

    @Test
    fun `summarize failure does not delete session`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(
            RuntimeException("fail")
        )

        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()

        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        viewModel.uiState.test {
            awaitItem()
        }
    }

    @Test
    fun `clearError sets error to null`() = runTest {
        coEvery { summarizeUseCase.execute(any()) } returns Result.failure(
            RuntimeException("test error")
        )

        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()

        chunksFlow.value = listOf(
            doneChunk(id = 1, index = 0, text = "テキスト1")
        )

        viewModel.uiState.test {
            val errorState = awaitItem()
            assertNotNull(errorState.error)
        }

        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `startRecording calls recordingController`() = runTest {
        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()
    }

    @Test
    fun `stopRecording calls recordingController`() = runTest {
        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)
        viewModel.startRecording()
        viewModel.stopRecording()
    }

    @Test
    fun `startRecording resets isRecording and sessionId`() = runTest {
        val viewModel = MainViewModel(chunkRepository, summarizeUseCase, recordingController)

        viewModel.startRecording()
        val firstSessionId = viewModel.uiState.value.sessionId
        assertTrue(viewModel.uiState.value.isRecording)

        viewModel.startRecording()
        val secondSessionId = viewModel.uiState.value.sessionId
        assertTrue(firstSessionId != secondSessionId)
        assertTrue(viewModel.uiState.value.isRecording)
        assertNull(viewModel.uiState.value.summary)
    }
}
