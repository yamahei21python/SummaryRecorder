package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var outputDir: File
    private lateinit var audioProvider: AudioProvider
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        outputDir = tempFolder.newFolder("recordings")
        audioProvider = mockk(relaxed = true)
        testScope = TestScope(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `start creates WAV file with valid header`() = testScope.runTest {
        val buffer = ShortArray(4096)
        every { audioProvider.read(any(), any()) } returns -1
        coEvery { audioProvider.start() } returns true

        val recorder = SimpleRecorder(outputDir, audioProvider, testScope)
        val file = recorder.start()

        assertTrue(file.exists(), "WAV file should be created")
        assertTrue(file.name.endsWith(".wav"), "File should have .wav extension")

        // WAVヘッダー検証 (Little-Endian)
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4)
            raf.read(riff)
            assertEquals("RIFF", String(riff))

            raf.seek(8)
            val wave = ByteArray(4)
            raf.read(wave)
            assertEquals("WAVE", String(wave))

            raf.seek(20)
            val audioFormat = readShortLE(raf)
            assertEquals(1, audioFormat, "PCM format = 1")

            raf.seek(22)
            val channels = readShortLE(raf)
            assertEquals(1, channels, "Mono = 1")

            raf.seek(24)
            val sampleRate = readIntLE(raf)
            assertEquals(16000, sampleRate, "16kHz sample rate")

            raf.seek(34)
            val bitsPerSample = readShortLE(raf)
            assertEquals(16, bitsPerSample, "16-bit")
        }
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val lo = raf.readByte().toInt() and 0xFF
        val hi = raf.readByte().toInt() and 0xFF
        return hi shl 8 or lo
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b0 = raf.readByte().toInt() and 0xFF
        val b1 = raf.readByte().toInt() and 0xFF
        val b2 = raf.readByte().toInt() and 0xFF
        val b3 = raf.readByte().toInt() and 0xFF
        return b3 shl 24 or (b2 shl 16) or (b1 shl 8) or b0
    }

    @Test
    fun `start throws when audioProvider start fails`() = testScope.runTest {
        coEvery { audioProvider.start() } returns false

        val recorder = SimpleRecorder(outputDir, audioProvider, testScope)

        var exception: Exception? = null
        try {
            recorder.start()
        } catch (e: IllegalStateException) {
            exception = e
        }

        assertTrue(exception is IllegalStateException, "Should throw IllegalStateException")
    }

    @Test
    fun `stop returns output file`() = testScope.runTest {
        every { audioProvider.read(any(), any()) } returns -1
        coEvery { audioProvider.start() } returns true

        val recorder = SimpleRecorder(outputDir, audioProvider, testScope)
        val startedFile = recorder.start()

        val stoppedFile = recorder.stop()

        assertEquals(startedFile, stoppedFile, "stop() should return the same file")
        assertTrue(stoppedFile!!.exists())
    }

    @Test
    fun `pause and resume toggle isPaused state`() = testScope.runTest {
        // 読み込みをブロックして録音継続
        every { audioProvider.read(any(), any()) } coAnswers {
            Thread.sleep(100)
            -1
        }
        coEvery { audioProvider.start() } returns true

        val recorder = SimpleRecorder(outputDir, audioProvider, testScope)
        recorder.start()

        assertFalse(recorder.isPaused)

        recorder.pause()
        assertTrue(recorder.isPaused, "Should be paused after pause()")

        coEvery { audioProvider.start() } returns true
        recorder.resume()
        assertFalse(recorder.isPaused, "Should not be paused after resume()")

        recorder.stop()
    }

    @Test
    fun `writePcmData writes correct bytes`() = testScope.runTest {
        val testData = shortArrayOf(100, 200, 300, 400)
        var readCount = 0
        every { audioProvider.read(any(), any()) } coAnswers {
            if (readCount == 0) {
                val buf = firstArg<ShortArray>()
                testData.forEachIndexed { i, v -> buf[i] = v }
                readCount++
                testData.size
            } else {
                -1
            }
        }
        coEvery { audioProvider.start() } returns true

        val recorder = SimpleRecorder(outputDir, audioProvider, testScope)
        val file = recorder.start()

        // 録音完了待ち
        testScope.testScheduler.advanceUntilIdle()

        recorder.stop()

        // PCMデータ検証（44byteヘッダー後）
        RandomAccessFile(file, "r").use { raf ->
            val dataLength = raf.length() - 44
            assertEquals(8L, dataLength, "4 samples × 2 bytes = 8 bytes PCM data")

            raf.seek(40)
            val chunkSize = readIntLE(raf)
            assertEquals(8, chunkSize, "data chunk size should be 8")
        }
    }

    @Test
    fun `empty recording creates valid WAV`() = testScope.runTest {
        every { audioProvider.read(any(), any()) } returns -1
        coEvery { audioProvider.start() } returns true

        val recorder = SimpleRecorder(outputDir, audioProvider, testScope)
        val file = recorder.start()
        recorder.stop()

        // ヘッダーのみ（44byte）
        assertEquals(44L, file.length(), "Empty recording should be header only")

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(40)
            val dataSize = readIntLE(raf)
            assertEquals(0, dataSize, "data size should be 0 for empty recording")
        }
    }
}
