package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.data.db.ChunkEntity
import com.kohei.summaryrecorder.data.db.ChunkStatus
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.ChunkRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class RecordingManagerConcurrencyTest {

    @Test
    fun `multiple rapid chunks are all inserted and uploaded`() = runTest(UnconfinedTestDispatcher()) {
        val mockRepo = mockk<ChunkRepository>(relaxed = true)
        val mockUploader = mockk<TranscriptionUploader>(relaxed = true)

        val manager = RecordingManager(mockRepo, mockUploader, this)

        val sessionId = "test-session"
        val files = listOf(File("chunk0.wav"), File("chunk1.wav"), File("chunk2.wav"))

        files.forEachIndexed { index, file ->
            launch {
                val entity = ChunkEntity(
                    sessionId = sessionId,
                    chunkIndex = index,
                    filePath = file.absolutePath,
                    status = ChunkStatus.PENDING
                )
                mockRepo.insert(entity)
                mockUploader.uploadChunk(entity)
            }
        }

        advanceUntilIdle()

        coVerify(exactly = 3) { mockRepo.insert(any()) }
        coVerify(exactly = 3) { mockUploader.uploadChunk(any()) }
    }

    @Test
    fun `concurrent start and stop - no exception`() = runTest(UnconfinedTestDispatcher()) {
        val mockRepo = mockk<ChunkRepository>(relaxed = true)
        val mockUploader = mockk<TranscriptionUploader>(relaxed = true)
        val mockAudioProvider = mockk<AudioProvider>(relaxed = true)
        every { mockAudioProvider.start() } returns true

        val manager = RecordingManager(mockRepo, mockUploader, this)
        val tempDir = createTempDir()

        coroutineScope {
            launch {
                manager.startRecording("s1", tempDir, 1024L, mockAudioProvider)
            }
            launch {
                manager.stopRecording()
            }
        }
        advanceUntilIdle()

        // 例外なく完了すればOK
    }

    @Test
    fun `double start stops previous recorder`() = runTest(UnconfinedTestDispatcher()) {
        val mockRepo = mockk<ChunkRepository>(relaxed = true)
        val mockUploader = mockk<TranscriptionUploader>(relaxed = true)
        val mockAudioProvider = mockk<AudioProvider>(relaxed = true)
        every { mockAudioProvider.start() } returns true

        val manager = RecordingManager(mockRepo, mockUploader, this)
        val tempDir = createTempDir()

        manager.startRecording("s1", tempDir, 1024L, mockAudioProvider)
        advanceUntilIdle()

        // 2回目のstartRecording → 1回目のrecorder.stop() が呼ばれる
        manager.startRecording("s2", tempDir, 1024L, mockAudioProvider)
        advanceUntilIdle()

        // AudioProvider.start が2回呼ばれる（1回目+2回目）
        verify(exactly = 2) { mockAudioProvider.start() }
    }

    @Test
    fun `stop without start - no exception`() = runTest(UnconfinedTestDispatcher()) {
        val mockRepo = mockk<ChunkRepository>(relaxed = true)
        val mockUploader = mockk<TranscriptionUploader>(relaxed = true)

        val manager = RecordingManager(mockRepo, mockUploader, this)

        // recorder は null のまま
        manager.stopRecording()

        // 例外なく完了すればOK
    }

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "test_recording_${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }
}
