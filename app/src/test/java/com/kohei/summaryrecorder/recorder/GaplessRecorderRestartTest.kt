package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GaplessRecorder: 再起動（start→stop→start）挙動検証。
 *
 * 検証項目:
 * - 再起動後、chunkIndexが0にリセットされる
 * - 同一出力ディレクトリの場合、前回ファイルが上書きされる
 * - 各セッションで有効なWAVファイルが生成される
 */
class GaplessRecorderRestartTest {

    @get:Rule
    val tempFolder = TemporaryFolder()
    
    private val tempDir: File by lazy { tempFolder.root }





    /** 何もしないAudioProvider（writeTestPcmData使用時用） */
    private val noopProvider = object : AudioProvider {
        override fun start(): Boolean = true
        override fun read(buffer: ShortArray, size: Int): Int = -1
        override fun stop() {}
        override fun release() {}
    }

    @Test
    fun `start stop start creates new chunk files`() = runTest {
        // セッション1: 100バイト書込み
        val recorder1 = GaplessRecorder(
            tempDir, 1024L, { _, _, _ -> }, noopProvider, this
        )
        recorder1.writeTestPcmData(ByteArray(100) { it.toByte() })
        recorder1.stopForTest()

        val chunks1 = tempDir.listFiles()!!.filter { it.name.startsWith("chunk_") }
        assertEquals(1, chunks1.size)
        assertEquals(44 + 100, chunks1[0].length())

        // セッション2: 200バイト書込み（同一ディレクトリ → chunk_0.wav上書き）
        val recorder2 = GaplessRecorder(
            tempDir, 1024L, { _, _, _ -> }, noopProvider, this
        )
        recorder2.writeTestPcmData(ByteArray(200) { it.toByte() })
        recorder2.stopForTest()

        val chunks2 = tempDir.listFiles()!!.filter { it.name.startsWith("chunk_") }
        assertEquals(1, chunks2.size, "同一ディレクトリではchunk_0.wavが上書きされる")
        assertEquals(44 + 200, chunks2[0].length(), "上書き後のファイルサイズがセッション2のデータ量")
    }

    @Test
    fun `restart resets chunkIndex to 0`() = runTest {
        // セッション1: 2チャンク生成（512バイト×2 > chunkSize=1024）
        val chunkSize = 1024L
        val recorder1 = GaplessRecorder(
            tempDir, chunkSize, { _, _, _ -> }, noopProvider, this
        )
        recorder1.writeTestPcmData(ByteArray(1024) { 0x01 })  // chunk_0
        recorder1.stopForTest()

        val filesAfterSession1 = tempDir.listFiles()!!.filter { it.name.startsWith("chunk_") }
        assertEquals(2, filesAfterSession1.size, "セッション1: 1データチャンク + 1空チャンク生成")
        assertTrue(filesAfterSession1.any { it.name == "chunk_0.wav" })
        assertTrue(filesAfterSession1.any { it.name == "chunk_1.wav" })

        // ファイルクリアして再起動
        filesAfterSession1.forEach { it.delete() }

        val recorder2 = GaplessRecorder(
            tempDir, chunkSize, { _, _, _ -> }, noopProvider, this
        )
        recorder2.writeTestPcmData(ByteArray(512) { 0x02 })  // chunk_0のみ
        recorder2.stopForTest()

        val filesAfterSession2 = tempDir.listFiles()!!.filter { it.name.startsWith("chunk_") }
        assertEquals(1, filesAfterSession2.size, "再起動後: chunkIndex=0から開始")
        assertEquals("chunk_0.wav", filesAfterSession2[0].name)
    }

    @Test
    fun `restart produces valid WAV after second session`() = runTest {
        // セッション1
        val recorder1 = GaplessRecorder(
            tempDir, 1024L, { _, _, _ -> }, noopProvider, this
        )
        recorder1.writeTestPcmData(ByteArray(50) { 0xAA.toByte() })
        recorder1.stopForTest()

        // ファイルクリア
        tempDir.listFiles()?.forEach { it.delete() }

        // セッション2: WAV妥当性検証
        val recorder2 = GaplessRecorder(
            tempDir, 1024L, { _, _, _ -> }, noopProvider, this
        )
        val data = ByteArray(300) { (it % 256).toByte() }
        recorder2.writeTestPcmData(data)
        recorder2.stopForTest()

        val wavFile = tempDir.listFiles()!!.first { it.name.startsWith("chunk_") }
        val bytes = wavFile.readBytes()

        // RIFFヘッダー検証
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])

        // WAVEマーカー
        assertEquals('W'.code.toByte(), bytes[8])
        assertEquals('A'.code.toByte(), bytes[9])
        assertEquals('V'.code.toByte(), bytes[10])
        assertEquals('E'.code.toByte(), bytes[11])

        // dataチャンク
        assertEquals('d'.code.toByte(), bytes[36])
        assertEquals('a'.code.toByte(), bytes[37])
        assertEquals('t'.code.toByte(), bytes[38])
        assertEquals('a'.code.toByte(), bytes[39])

        // ファイルサイズ = ヘッダー44 + PCMデータ300
        assertEquals(44 + 300, wavFile.length())
    }
}
