package com.kohei.summaryrecorder.service

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingManagerConcurrencyTest {

    @Test
    fun `concurrent start and stop - no exception`() = runTest(UnconfinedTestDispatcher()) {
        val mockAudioProvider = mockk<AudioProvider>(relaxed = true)
        every { mockAudioProvider.start() } returns true
        every { mockAudioProvider.read(any(), any()) } returns -1

        val manager = RecordingManager(this)
        val tempDir = createTempDir()

        coroutineScope {
            launch {
                manager.startRecording("s1", tempDir, mockAudioProvider)
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
        val mockAudioProvider = mockk<AudioProvider>(relaxed = true)
        every { mockAudioProvider.start() } returns true
        every { mockAudioProvider.read(any(), any()) } returns -1

        val manager = RecordingManager(this)
        val tempDir = createTempDir()

        manager.startRecording("s1", tempDir, mockAudioProvider)
        advanceUntilIdle()

        // 2回目のstartRecording → 1回目のrecorder.stop() が呼ばれる
        manager.startRecording("s2", tempDir, mockAudioProvider)
        advanceUntilIdle()

        // AudioProvider.start が2回呼ばれる（1回目+2回目）
        verify(exactly = 2) { mockAudioProvider.start() }
    }

    @Test
    fun `stop without start - no exception`() = runTest(UnconfinedTestDispatcher()) {
        val manager = RecordingManager(this)

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
