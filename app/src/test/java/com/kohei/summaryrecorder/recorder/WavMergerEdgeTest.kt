package com.kohei.summaryrecorder.recorder

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavMergerEdgeTest {

    private lateinit var tempDir: File
    private val headerSize = 44

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "wavmerger_edge_${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createWav(name: String, pcmSize: Int): File {
        val file = File(tempDir, name)
        RandomAccessFile(file, "rw").use { raf ->
            WavHeaderWriter.writeHeader(raf, pcmSize.toLong())
            if (pcmSize > 0) {
                raf.write(ByteArray(pcmSize) { (it % 256).toByte() })
            }
        }
        return file
    }

    /** PCM=0チャンクが混在していても、全チャンクが削除されること */
    @Test
    fun `PCM zero chunks are still deleted after merge`() {
        val c0 = createWav("chunk_0.wav", 0)     // PCM=0
        val c1 = createWav("chunk_1.wav", 100)    // PCM=100
        val output = File(tempDir, "merged.wav")

        WavMerger.merge(listOf(c0, c1), output)

        // PCM=0のチャンクも削除される
        assertTrue(!c0.exists(), "PCM=0 chunk should be deleted")
        assertTrue(!c1.exists(), "Normal chunk should be deleted")
        // 結合ファイルは正常サイズ
        assertEquals((headerSize + 100).toLong(), output.length())
    }

    /** 全チャンクがPCM=0でも結合ファイルが生成されること（ヘッダーのみ） */
    @Test
    fun `all zero PCM chunks produces header-only file`() {
        val c0 = createWav("chunk_0.wav", 0)
        val c1 = createWav("chunk_1.wav", 0)
        val output = File(tempDir, "merged.wav")

        val duration = WavMerger.merge(listOf(c0, c1), output)

        assertEquals(headerSize.toLong(), output.length())
        assertEquals(0L, duration)
        assertTrue(!c0.exists())
        assertTrue(!c1.exists())
    }

    /** 不正ヘッダー（サイズ不足ファイル）が含まれていてもクラッシュしないこと */
    @Test
    fun `truncated file smaller than header size is handled`() {
        val c0 = File(tempDir, "chunk_truncated.wav")
        c0.writeBytes(ByteArray(10) { 0 }) // ヘッダーより小さい
        val c1 = createWav("chunk_1.wav", 100)
        val output = File(tempDir, "merged.wav")

        // pcmSize = file.length() - 44 = 10 - 44 = -34 → <= 0 → skip
        // クラッシュせずにc1のみ結合される
        WavMerger.merge(listOf(c0, c1), output)

        assertEquals((headerSize + 100).toLong(), output.length())
        assertTrue(!c0.exists())
        assertTrue(!c1.exists())
    }

    /** 大きなPCMデータでも正確に結合されること */
    @Test
    fun `large file merge preserves all data`() {
        val largePcmSize = 1024 * 1024 // 1MB PCM
        val c0 = createWav("chunk_0.wav", largePcmSize)
        val c1 = createWav("chunk_1.wav", largePcmSize)
        val output = File(tempDir, "merged.wav")

        WavMerger.merge(listOf(c0, c1), output)

        val expectedSize = headerSize + largePcmSize * 2
        assertEquals(expectedSize.toLong(), output.length())
    }

    /** calcDuration正確性: 32000 bytes = 1秒 (16000Hz, 16bit, mono) */
    @Test
    fun `duration calculation with large file`() {
        val pcmBytes = 32000 * 60 // 60秒分
        val chunk = createWav("chunk_0.wav", pcmBytes)
        val output = File(tempDir, "merged.wav")

        val duration = WavMerger.merge(listOf(chunk), output)

        assertEquals(60000L, duration)
    }

    /** 3チャンク以上の結合でPCMデータ順序が保持されること */
    @Test
    fun `merge preserves chunk order`() {
        val c0 = File(tempDir, "c0.wav")
        RandomAccessFile(c0, "rw").use { raf ->
            WavHeaderWriter.writeHeader(raf, 2)
            raf.write(byteArrayOf(0x01, 0x02))
        }
        val c1 = File(tempDir, "c1.wav")
        RandomAccessFile(c1, "rw").use { raf ->
            WavHeaderWriter.writeHeader(raf, 2)
            raf.write(byteArrayOf(0x03, 0x04))
        }
        val c2 = File(tempDir, "c2.wav")
        RandomAccessFile(c2, "rw").use { raf ->
            WavHeaderWriter.writeHeader(raf, 2)
            raf.write(byteArrayOf(0x05, 0x06))
        }
        val output = File(tempDir, "merged.wav")

        WavMerger.merge(listOf(c0, c1, c2), output)

        val bytes = output.readBytes()
        assertEquals(0x01, bytes[44].toInt())
        assertEquals(0x02, bytes[45].toInt())
        assertEquals(0x03, bytes[46].toInt())
        assertEquals(0x04, bytes[47].toInt())
        assertEquals(0x05, bytes[48].toInt())
        assertEquals(0x06, bytes[49].toInt())
    }
}
