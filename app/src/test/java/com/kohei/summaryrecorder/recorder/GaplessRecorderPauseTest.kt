package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GaplessRecorderPauseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()
    private val tempDir: File by lazy { tempFolder.root }

    @Test
    fun `pause sets isPaused true`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val provider = TestAudioProvider()
        val recorder = GaplessRecorder(
            tempDir, 1024L, { _, _, _ -> }, provider, scope
        )
        recorder.start()
        Thread.sleep(50)

        recorder.pause()

        assertTrue(recorder.isPaused)
        assertTrue(recorder.isRecording, "isRecording stays true during pause")

        recorder.stop()
        scope.cancel()
    }

    @Test
    fun `resume sets isPaused false`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val provider = TestAudioProvider()
        val recorder = GaplessRecorder(
            tempDir, 1024L, { _, _, _ -> }, provider, scope
        )
        recorder.start()
        Thread.sleep(50)
        recorder.pause()

        recorder.resume()

        assertFalse(recorder.isPaused)
        assertTrue(recorder.isRecording)

        recorder.stop()
        scope.cancel()
    }

    @Test
    fun `pause then stop produces valid file`() = runBlocking {
        val chunks = mutableListOf<File>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val provider = TestAudioProvider()
        val recorder = GaplessRecorder(
            tempDir, 1024L,
            { _, file, _ -> chunks.add(file) },
            provider,
            scope
        )
        recorder.start()
        Thread.sleep(200) // データ書込を待つ
        recorder.pause()
        recorder.stop()

        assertTrue(chunks.isNotEmpty(), "chunks should be generated, got ${chunks.size}")
        chunks.forEach { chunk ->
            assertTrue(chunk.length() >= WavHeaderWriter.HEADER_SIZE, "WAV has valid header")
        }
        scope.cancel()
    }

    @Test
    fun `pause is no-op when not recording`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val provider = TestAudioProvider()
        val recorder = GaplessRecorder(
            tempDir, 1024L, { _, _, _ -> }, provider, scope
        )
        recorder.pause()
        assertFalse(recorder.isPaused)
        scope.cancel()
    }

    @Test
    fun `resume is no-op when not paused`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val provider = TestAudioProvider()
        val recorder = GaplessRecorder(
            tempDir, 1024L, { _, _, _ -> }, provider, scope
        )
        recorder.start()
        Thread.sleep(50)
        recorder.resume()
        assertFalse(recorder.isPaused)
        recorder.stop()
        scope.cancel()
    }

    private class TestAudioProvider : AudioProvider {
        private var active = false

        override fun start(): Boolean {
            active = true
            return true
        }

        override fun read(buffer: ShortArray, size: Int): Int {
            if (!active) return -1
            Thread.sleep(1) // 少し遅延してループが高速回転しないように
            for (i in 0 until minOf(size, buffer.size)) {
                buffer[i] = (i % 256).toShort()
            }
            return size
        }

        override fun stop() { active = false }
        override fun release() { active = false }
    }
}
