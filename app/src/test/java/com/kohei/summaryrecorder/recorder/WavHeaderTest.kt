package com.kohei.summaryrecorder.recorder

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals

/**
 * WavHeaderWriter: 44byte構造検証
 */
class WavHeaderTest {

    @TempDir
    lateinit var tempDir: File

    private fun createRaf(fileName: String): RandomAccessFile {
        return RandomAccessFile(File(tempDir, fileName), "rw")
    }

    @Test
    fun `writeHeader produces exactly 44 bytes`() {
        val raf = createRaf("test.wav")
        WavHeaderWriter.writeHeader(raf, dataLength = 1000)
        raf.close()

        val file = File(tempDir, "test.wav")
        // writeHeader only writes the 44-byte header, not the PCM data
        assertEquals(44L, file.length())
    }

    @Test
    fun `RIFF header is correct`() {
        val raf = createRaf("test.wav")
        WavHeaderWriter.writeHeader(raf, dataLength = 500)
        raf.close()

        val bytes = File(tempDir, "test.wav").readBytes()
        // "RIFF"
        assertEquals('R'.code, bytes[0].toInt())
        assertEquals('I'.code, bytes[1].toInt())
        assertEquals('F'.code, bytes[2].toInt())
        assertEquals('F'.code, bytes[3].toInt())
    }

    @Test
    fun `chunk size equals fileSize minus 8`() {
        val dataLength = 12345L
        val raf = createRaf("test.wav")
        WavHeaderWriter.writeHeader(raf, dataLength = dataLength)
        raf.close()

        val bytes = File(tempDir, "test.wav").readBytes()
        val chunkSize = ByteBuffer.wrap(bytes, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals((44 + dataLength - 8).toInt(), chunkSize)
    }

    @Test
    fun `WAVE marker at offset 8`() {
        val raf = createRaf("test.wav")
        WavHeaderWriter.writeHeader(raf, dataLength = 100)
        raf.close()

        val bytes = File(tempDir, "test.wav").readBytes()
        assertEquals('W'.code, bytes[8].toInt())
        assertEquals('A'.code, bytes[9].toInt())
        assertEquals('V'.code, bytes[10].toInt())
        assertEquals('E'.code, bytes[11].toInt())
    }

    @Test
    fun `fmt sub-chunk has PCM format`() {
        val raf = createRaf("test.wav")
        WavHeaderWriter.writeHeader(raf, dataLength = 0)
        raf.close()

        val bytes = File(tempDir, "test.wav").readBytes()
        // "fmt " at offset 12
        assertEquals('f'.code, bytes[12].toInt())
        assertEquals('m'.code, bytes[13].toInt())
        assertEquals('t'.code, bytes[14].toInt())
        assertEquals(' '.code, bytes[15].toInt())

        // fmt chunk size = 16
        val fmtSize = ByteBuffer.wrap(bytes, 16, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(16, fmtSize)

        // audioFormat = 1 (PCM)
        val audioFormat = ByteBuffer.wrap(bytes, 20, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short
        assertEquals(1, audioFormat.toInt())
    }

    @Test
    fun `default audio params are 16kHz mono 16bit`() {
        val raf = createRaf("test.wav")
        WavHeaderWriter.writeHeader(raf, dataLength = 0)
        raf.close()

        val bytes = File(tempDir, "test.wav").readBytes()
        val numChannels = ByteBuffer.wrap(bytes, 22, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short
        val sampleRate = ByteBuffer.wrap(bytes, 24, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        val bitsPerSample = ByteBuffer.wrap(bytes, 34, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short

        assertEquals(1, numChannels.toInt())  // mono
        assertEquals(16000, sampleRate)        // 16kHz
        assertEquals(16, bitsPerSample.toInt()) // 16bit
    }

    @Test
    fun `data sub-chunk header at offset 36`() {
        val dataLength = 999L
        val raf = createRaf("test.wav")
        WavHeaderWriter.writeHeader(raf, dataLength = dataLength)
        raf.close()

        val bytes = File(tempDir, "test.wav").readBytes()
        assertEquals('d'.code, bytes[36].toInt())
        assertEquals('a'.code, bytes[37].toInt())
        assertEquals('t'.code, bytes[38].toInt())
        assertEquals('a'.code, bytes[39].toInt())

        val dataChunkSize = ByteBuffer.wrap(bytes, 40, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(dataLength.toInt(), dataChunkSize)
    }

    @Test
    fun `writeDummyHeader writes dataLength zero`() {
        val raf = createRaf("dummy.wav")
        WavHeaderWriter.writeDummyHeader(raf)
        raf.close()

        val bytes = File(tempDir, "dummy.wav").readBytes()
        assertEquals(44, bytes.size)

        val dataChunkSize = ByteBuffer.wrap(bytes, 40, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0, dataChunkSize)

        val chunkSize = ByteBuffer.wrap(bytes, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(36, chunkSize) // RIFF chunk size = 44 - 8
    }
}
