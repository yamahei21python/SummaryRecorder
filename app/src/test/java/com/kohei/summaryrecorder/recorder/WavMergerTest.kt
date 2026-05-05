package com.kohei.summaryrecorder.recorder

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavMergerTest {

    private lateinit var tempDir: File
    private val headerSize = 44

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "wavmerger_test_${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** PCMデータ付きWAVテストファイルを生成 */
    private fun createWav(name: String, pcmSize: Int): File {
        val file = File(tempDir, name)
        RandomAccessFile(file, "rw").use { raf ->
            WavHeaderWriter.writeHeader(raf, pcmSize.toLong())
            raf.write(ByteArray(pcmSize) { (it % 256).toByte() })
        }
        return file
    }

    @Test
    fun `single file shortcut copies exactly`() {
        val chunk = createWav("chunk_0.wav", 100)
        val output = File(tempDir, "merged.wav")

        val duration = WavMerger.merge(listOf(chunk), output)

        assertEquals(headerSize + 100, output.length().toInt())
        assertTrue(duration > 0)
        assertTrue(!chunk.exists(), "元チャンクは削除されること")
    }

    @Test
    fun `two files merged correctly`() {
        val c1 = createWav("chunk_0.wav", 100)
        val c2 = createWav("chunk_1.wav", 200)
        val output = File(tempDir, "merged.wav")

        WavMerger.merge(listOf(c1, c2), output)

        // ヘッダー(44) + PCM(100+200)
        assertEquals((headerSize + 300).toLong(), output.length())

        // RIFFヘッダー検証
        val bytes = output.readBytes()
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])

        // data size = 300 (LE)
        val dataSize = bytes[40].toLong() and 0xFF or
                ((bytes[41].toLong() and 0xFF) shl 8) or
                ((bytes[42].toLong() and 0xFF) shl 16) or
                ((bytes[43].toLong() and 0xFF) shl 24)
        assertEquals(300L, dataSize)

        assertTrue(!c1.exists())
        assertTrue(!c2.exists())
    }

    @Test
    fun `duration is correctly calculated`() {
        // SAMPLE_RATE=16000, 16bit mono → 32000 bytes/sec
        // 32000 bytes = 1秒 = 1000ms
        val pcmBytes = 32000
        val chunk = createWav("chunk_0.wav", pcmBytes)
        val output = File(tempDir, "merged.wav")

        val duration = WavMerger.merge(listOf(chunk), output)

        assertEquals(1000L, duration)
    }

    @Test
    fun `skips zero-byte PCM chunks`() {
        val c1 = createWav("chunk_0.wav", 0) // PCM=0（ヘッダーのみ）
        val c2 = createWav("chunk_1.wav", 100)
        val output = File(tempDir, "merged.wav")

        WavMerger.merge(listOf(c1, c2), output)

        assertEquals((headerSize + 100).toLong(), output.length())
    }

    @Test
    fun `chunks deleted after merge`() {
        val chunks = listOf(
            createWav("chunk_0.wav", 50),
            createWav("chunk_1.wav", 50),
            createWav("chunk_2.wav", 50)
        )
        val output = File(tempDir, "merged.wav")

        WavMerger.merge(chunks, output)

        chunks.forEach { chunk ->
            assertTrue(!chunk.exists(), "${chunk.name} should be deleted")
        }
        assertTrue(output.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty list throws exception`() {
        WavMerger.merge(emptyList(), File(tempDir, "merged.wav"))
    }

    @Test
    fun `PCM data preserved after merge`() {
        // c1: PCM = [1,2,3], c2: PCM = [4,5,6]
        val c1 = File(tempDir, "c1.wav")
        RandomAccessFile(c1, "rw").use { raf ->
            WavHeaderWriter.writeHeader(raf, 3)
            raf.write(byteArrayOf(1, 2, 3))
        }
        val c2 = File(tempDir, "c2.wav")
        RandomAccessFile(c2, "rw").use { raf ->
            WavHeaderWriter.writeHeader(raf, 3)
            raf.write(byteArrayOf(4, 5, 6))
        }
        val output = File(tempDir, "merged.wav")

        WavMerger.merge(listOf(c1, c2), output)

        val bytes = output.readBytes()
        // PCM data starts at offset 44
        assertEquals(1, bytes[44].toInt())
        assertEquals(2, bytes[45].toInt())
        assertEquals(3, bytes[46].toInt())
        assertEquals(4, bytes[47].toInt())
        assertEquals(5, bytes[48].toInt())
        assertEquals(6, bytes[49].toInt())
    }
}
