package com.kohei.summaryrecorder.recorder

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GaplessRecorder: PCM書込み→WAVファイル生成の正常性
 *
 * AudioRecordはモック不可（ネイティブ）なので、
 * GaplessRecorderの内部ロジックを直接テストするため、
 * テスト用ヘルパーでPCMデータを注入する。
 *
 * ※ AudioRecordを使わないファイル書込みロジックに焦点を当てる。
 */
class GaplessRecorderWriteTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `single chunk file has valid WAV header after stop`() = runTest {
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 19L * 1024 * 1024,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) }
        )

        // PCMデータを書き込む（1000 bytes = 500 samples @ 16bit mono）
        val pcmSize = 1000L
        recorder.writeTestPcmData(ByteArray(pcmSize.toInt()) { it.toByte() })

        // stop でヘッダー確定
        recorder.stopForTest()

        // 1チャンク生成されている
        assertEquals(1, recordedChunks.size)
        val (_, file) = recordedChunks[0]

        // WAVヘッダー検証
        val bytes = file.readBytes()
        assertTrue(bytes.size >= 44)

        // "RIFF"
        assertEquals("RIFF", String(bytes, 0, 4))

        // dataLength
        val dataChunkSize = ByteBuffer.wrap(bytes, 40, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(pcmSize.toInt(), dataChunkSize)

        // ファイルサイズ = ヘッダー + データ
        assertEquals(44 + pcmSize, file.length())
    }

    @Test
    fun `empty recording produces no chunk`() = runTest {
        val recordedChunks = mutableListOf<Pair<Int, File>>()
        val recorder = GaplessRecorder(
            outputDir = tempDir,
            chunkSizeBytes = 19L * 1024 * 1024,
            onChunkComplete = { index, file -> recordedChunks.add(index to file) }
        )

        // 何も書かずにstop
        recorder.stopForTest()

        // データなし → チャンク生成なし
        assertEquals(0, recordedChunks.size)
    }
}
