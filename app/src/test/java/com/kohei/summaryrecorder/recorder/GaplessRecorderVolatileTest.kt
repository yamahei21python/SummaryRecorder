package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GaplessRecorderVolatileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val noopProvider = object : AudioProvider {
        override fun start(): Boolean = true
        override fun read(buffer: ShortArray, size: Int): Int {
            Thread.sleep(10) // ループに少し遅延を入れる
            return size
        }
        override fun stop() {}
        override fun release() {}
    }

    @Test
    fun `stop from different coroutine context terminates recording loop`() = runTest {
        val recorder = GaplessRecorder(
            outputDir = tempFolder.root,
            chunkSizeBytes = 1024L * 1024,
            onChunkComplete = { _, _ -> },
            audioProvider = noopProvider,
            coroutineScope = CoroutineScope(Dispatchers.Default)
        )

        val job = launch {
            recorder.start()
        }

        advanceUntilIdle()
        assertTrue(recorder.isRecording, "録音中であること")

        // 別スレッドから stop() を呼び出す
        withContext(Dispatchers.IO) {
            recorder.stop()
        }

        advanceUntilIdle()
        assertFalse(recorder.isRecording, "stop呼び出し後は false になること")
        job.cancelAndJoin()
    }
}
