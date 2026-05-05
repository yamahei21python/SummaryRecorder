package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.provider.AudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GaplessRecorder: 19MB到達時の自動分割動作検証
 *
 * テストでは小さいチャンクサイズを使い、
 * 複数回のwriteTestPcmDataで分割をトリガーする。
 */
class GaplessRecorderSplitTest {

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
    fun `splits into multiple chunks when exceeding chunkSizeBytes`() = runTest {
        // 小さいチャンクサイズ（512 bytes）
        val chunkSize = 512L
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = chunkSize,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = noopProvider,
            coroutineScope = testScope
        )

        // 3回に分けて書込み（各256 bytes） → 3回目で累計768 > 512 → 分割
        recorder.writeTestPcmData(ByteArray(256) { it.toByte() })  // 累計256
        recorder.writeTestPcmData(ByteArray(256) { it.toByte() })  // 累計512 → == chunkSize → 分割トリガー
        recorder.writeTestPcmData(ByteArray(256) { it.toByte() })  // 新チャンク: 256
        recorder.stopForTest()

        // チャンク1（512 bytes）+ チャンク2（256 bytes）
        assertTrue(recordedChunks.size >= 2, "Expected >= 2 chunks, got ${recordedChunks.size}")

        // 最初のチャンクのデータ長が chunkSize に一致
        val firstFile = recordedChunks[0].second
        val firstBytes = firstFile.readBytes()
        val firstDataLen = ByteBuffer.wrap(firstBytes, 40, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(512, firstDataLen)
    }

    @Test
    fun `chunk indices are sequential`() = runTest {
        val chunkSize = 200L
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = chunkSize,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = noopProvider,
            coroutineScope = testScope
        )

        // 200 bytesずつ書込み → 毎回分割トリガー
        recorder.writeTestPcmData(ByteArray(200) { 0xAB.toByte() })
        recorder.writeTestPcmData(ByteArray(200) { 0xCD.toByte() })
        recorder.writeTestPcmData(ByteArray(200) { 0xEF.toByte() })
        recorder.stopForTest()

        // インデックスが0, 1, 2, 3と連番（stopForTestで最終チャンクも確定）
        for (i in recordedChunks.indices) {
            assertEquals(i, recordedChunks[i].first, "Expected index $i but got ${recordedChunks[i].first}")
        }
    }

    @Test
    fun `all produced files are valid WAV`() = runTest {
        val chunkSize = 200L
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = chunkSize,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = noopProvider,
            coroutineScope = testScope
        )

        recorder.writeTestPcmData(ByteArray(200) { 0.toByte() })
        recorder.writeTestPcmData(ByteArray(200) { 0.toByte() })
        recorder.writeTestPcmData(ByteArray(200) { 0.toByte() })
        recorder.stopForTest()

        for ((_, file) in recordedChunks) {
            val bytes = file.readBytes()
            assertTrue(bytes.size >= 44, "File too small: ${file.name}")
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
        }
    }
}
