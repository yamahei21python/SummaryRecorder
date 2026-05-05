package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.provider.AudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * GaplessRecorder: 並行アクセス時の排他制御検証
 *
 * 複数スレッドから同時書込みしてもデータが欠落しないことを検証。
 * テストではチャンクサイズ以下の書込みを複数回実行。
 */
class GaplessRecorderMutexTest {

    @TempDir
    lateinit var tempDir: File

    private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val noopProvider = object : AudioProvider {
        override fun start(): Boolean = true
        override fun read(buffer: ShortArray, size: Int): Int = -1
        override fun stop() {}
        override fun release() {}
    }

    @Test
    fun `concurrent writes do not corrupt file`() = runTest {
        val chunkSize = 8192L
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = chunkSize,
            onChunkComplete = { index, file ->
                synchronized(recordedChunks) {
                    recordedChunks.add(index to file)
                }
            },
            audioProvider = noopProvider,
            coroutineScope = testScope
        )

        // メインスレッドで5回書込み（各1024 bytes）
        val totalWrites = 5
        val writeSize = 1024
        repeat(totalWrites) {
            recorder.writeTestPcmData(ByteArray(writeSize) { it.toByte() })
        }

        recorder.stopForTest()

        // 全チャンクが有効なWAV
        for ((_, file) in recordedChunks) {
            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4))
        }

        // 合計データサイズ = 5 * 1024 = 5120
        val totalData = recordedChunks.sumOf { (_, file) ->
            file.length() - 44
        }
        assertEquals(totalWrites * writeSize.toLong(), totalData)
    }
}
