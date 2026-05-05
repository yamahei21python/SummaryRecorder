package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * GaplessRecorder: 二重start()デッドロック検証。
 *
 * start()先頭でstopInternal()呼ぶため、recordingJobのcancelAndJoin()が
 * 外側mutexを保持したまま内側mutexを待つ構造になり得る。
 * UnconfinedTestDispatcher + timeoutでデッドロック発生時の検出を確認。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GaplessRecorderDoubleStartDeadlockTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val tempDir: File by lazy { tempFolder.root }

    /** 指定回数read後に-1を返すprovider */
    private fun createFiniteProvider(readCount: Int): AudioProvider = object : AudioProvider {
        private var count = 0
        override fun start(): Boolean = true
        override fun stop() {}
        override fun release() {}
        override fun read(buffer: ShortArray, size: Int): Int {
            if (count >= readCount) return -1
            for (i in buffer.indices) buffer[i] = (count % 256).toShort()
            count++
            return size
        }
    }

    @Test
    fun `double start completes without deadlock`() = runTest {
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 1024L,
            onChunkComplete = { _, _, _ -> },
            audioProvider = createFiniteProvider(readCount = 5),
            coroutineScope = this
        )

        // 2秒タイムアウト付きで二重start — デッドロックならTimeoutException
        val completed = kotlinx.coroutines.withTimeout(2_000) {
            recorder.start()
            recorder.start()
            true
        }

        assertTrue(completed, "二重startがデッドロックせず2秒以内に完了すべき")
    }

    @Test
    fun `start stop start completes without deadlock`() = runTest {
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 1024L,
            onChunkComplete = { _, _, _ -> },
            audioProvider = createFiniteProvider(readCount = 5),
            coroutineScope = this
        )

        val completed = kotlinx.coroutines.withTimeout(2_000) {
            recorder.start()
            recorder.stop()
            recorder.start()
            recorder.stop()
            true
        }

        assertTrue(completed, "start→stop→startシーケンスが2秒以内に完了すべき")
        assertFalse(recorder.isRecording, "最終的に録音状態がfalse")
    }
}
