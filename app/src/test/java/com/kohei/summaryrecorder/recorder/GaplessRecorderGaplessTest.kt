package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.provider.AudioProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GaplessRecorder: 分割前後でデータ欠落ゼロの確認
 */
class GaplessRecorderGaplessTest {

    @TempDir
    lateinit var tempDir: File

    private val noopProvider = object : AudioProvider {
        override fun start(): Boolean = true
        override fun read(buffer: ShortArray, size: Int): Int = -1
        override fun stop() {}
        override fun release() {}
    }

    @Test
    fun `no data loss across chunk boundaries`() = runTest {
        val chunkSize = 200L
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = chunkSize,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = noopProvider
        )

        // 200 bytesずつ（一意パターン）5回書込み → 1000 bytes total
        val allData = ByteArray(1000) { i -> (i % 251).toByte() }
        for (offset in 0 until 1000 step 200) {
            recorder.writeTestPcmData(allData.copyOfRange(offset, offset + 200))
        }
        recorder.stopForTest()

        // チャンクをインデックス順に結合し、PCMデータを取り出す
        val sortedChunks = recordedChunks.sortedBy { it.first }
        val reconstructed = mutableListOf<Byte>()
        for ((_, file) in sortedChunks) {
            val bytes = file.readBytes()
            for (i in 44 until bytes.size) {
                reconstructed.add(bytes[i])
            }
        }

        // 元のデータと完全一致
        assertEquals(1000, reconstructed.size)
        for (i in 0 until 1000) {
            assertEquals(allData[i], reconstructed[i], "Mismatch at byte $i")
        }
    }

    @Test
    fun `exact chunk boundary produces full chunks only`() = runTest {
        val chunkSize = 200L
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = chunkSize,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = noopProvider
        )

        // 200 + 200 = 2チャンクぴったり
        recorder.writeTestPcmData(ByteArray(200) { 0x55.toByte() })
        recorder.writeTestPcmData(ByteArray(200) { 0x55.toByte() })
        recorder.stopForTest()

        // 2チャンク。各200 byte。
        // ※ stopForTestは空ファイルなのでチャンク追加なし
        assertTrue(recordedChunks.size >= 2)
        for ((_, file) in recordedChunks) {
            assertEquals(44 + 200, file.length())
        }
    }
}
