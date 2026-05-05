package com.kohei.summaryrecorder.recorder

import org.junit.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals

class WavHeaderWriterEdgeTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `writeHeader with large dataLength produces correct RIFF size`() {
        val file = File(tempDir, "test.wav")
        val raf = RandomAccessFile(file, "rw")
        
        // 1GBのデータ長を想定
        val dataLength = 1024L * 1024 * 1024 
        WavHeaderWriter.writeHeader(raf, dataLength)

        raf.seek(4)
        val buffer = ByteArray(4)
        raf.read(buffer)
        val riffSize = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        
        // RIFF size = 36 + dataLength
        assertEquals(36L + dataLength, riffSize)
        raf.close()
    }
}
