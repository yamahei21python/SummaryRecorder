package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.audio.AudioProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GaplessRecorder: エッジケーステスト。
 *
 * 検証項目:
 * - AudioProvider.start()失敗 → IllegalStateException
 * - start直後にstop → 0バイトチャンク → onChunkComplete呼ばれない
 * - 丁度chunkSizeBytes境界 → 分割タイミング正確性
 * - stop() 2回連続 → 安全（冪等性）
 */
class GaplessRecorderEdgeTest {

    @TempDir
    lateinit var tempDir: File

    // ===== AudioProvider.start() 失敗 =====

    @Test
    fun `AudioProvider start failure throws IllegalStateException`(): Unit = runTest {
        val failingProvider = object : AudioProvider {
            override fun start(): Boolean = false
            override fun read(buffer: ShortArray, size: Int): Int = -1
            override fun stop() {}
            override fun release() {}
        }

        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 1024,
            onChunkComplete = { _, _ -> },
            audioProvider = failingProvider
        )

        assertThrows<IllegalStateException> {
            recorder.start()
        }
    }

    // ===== start直後にstop → 0バイトチャンク =====

    @Test
    fun `start then immediate stop produces no chunks`() = runTest {
        val recordedChunks = mutableListOf<Pair<Int, File>>()

        val provider = object : AudioProvider {
            @Volatile private var active = false
            override fun start(): Boolean { active = true; return true }
            override fun read(buffer: ShortArray, size: Int): Int {
                return if (active) {
                    Thread.sleep(10) // 少し待つ
                    -1 // 即EOF
                } else -1
            }
            override fun stop() { active = false }
            override fun release() { active = false }
        }

        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 1024,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = provider
        )

        recorder.start()
        Thread.sleep(100) // コルーチン起動待ち
        recorder.stop()

        // データ0バイト → onChunkComplete呼ばれない
        assertEquals(0, recordedChunks.size)
    }

    // ===== 0バイト書込み（writeTestPcmData使用）=====

    @Test
    fun `writeTestPcmData with zero bytes then stop produces no chunks`() = runTest {
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 1024,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) }
        )

        // 0バイト書込み → 何もしない
        recorder.writeTestPcmData(ByteArray(0))
        recorder.stopForTest()

        assertEquals(0, recordedChunks.size)
    }

    // ===== 丁度chunkSizeBytes境界 =====

    @Test
    fun `exact chunkSizeBytes produces single full chunk`() = runTest {
        val chunkSize = 256L
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = chunkSize,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) }
        )

        // 丁度256バイト書込み → 分割トリガー（== chunkSizeBytes）
        recorder.writeTestPcmData(ByteArray(256) { it.toByte() })
        recorder.stopForTest()

        // 1チャンク256バイト + stopForTestは0バイトなので追加なし
        assertTrue(recordedChunks.size >= 1)
        val firstFile = recordedChunks[0].second
        assertEquals(44 + 256, firstFile.length())
    }

    @Test
    fun `one byte over chunkSizeBytes produces full chunk plus partial`() = runTest {
        val chunkSize = 256L
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = chunkSize,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) }
        )

        // 257バイト書込み → 256バイトチャンク + 1バイト残り
        recorder.writeTestPcmData(ByteArray(257) { it.toByte() })
        recorder.stopForTest()

        // チャンク1(256) + チャンク2(1) → stopForTestで確定
        assertTrue(recordedChunks.size >= 2, "Expected >= 2 chunks, got ${recordedChunks.size}")

        // 最初のチャンクは256バイト
        val firstDataLen = recordedChunks[0].second.length() - 44
        assertEquals(256, firstDataLen)
    }

    // ===== 二重stop安全性（stopForTest） =====

    @Test
    fun `double stopForTest does not crash`() = runTest {
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 1024,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) }
        )

        recorder.writeTestPcmData(ByteArray(100) { it.toByte() })
        recorder.stopForTest()

        // 2回目のstopForTest → 安全
        recorder.stopForTest()

        assertEquals(1, recordedChunks.size)
    }

    // ===== AudioProvider readが0返す =====

    @Test
    fun `AudioProvider read returns 0 breaks recording loop`(): Unit = runTest {
        val recordedChunks = mutableListOf<Pair<Int, File>>()

        val provider = object : AudioProvider {
            override fun start(): Boolean = true
            override fun read(buffer: ShortArray, size: Int): Int = 0 // 0 = ループ脱出
            override fun stop() {}
            override fun release() {}
        }

        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 1024,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) },
            audioProvider = provider
        )

        recorder.start()
        Thread.sleep(100) // コルーチン完了待ち
        recorder.stop()

        assertEquals(0, recordedChunks.size)
    }
}
