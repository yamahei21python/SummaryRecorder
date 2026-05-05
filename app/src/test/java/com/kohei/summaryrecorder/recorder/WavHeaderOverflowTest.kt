package com.kohei.summaryrecorder.recorder

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals

/**
 * WavHeaderWriter: dataLengthオーバーフロー検証。
 *
 * WAVフォーマットの制約により、data sub-chunk sizeは32bit unsigned。
 * writeHeaderはtoInt()でキャストするため、Int.MAX_VALUE超過で意図しない値になる。
 *
 * 検証項目:
 * - Int.MAX_VALUEちょうど → 正しい値が書込まれる
 * - Int.MAX_VALUE + 1 → オーバーフローで負の値になる（WAV仕様上の制限）
 */
class WavHeaderOverflowTest {

    @TempDir
    lateinit var tempDir: File

    private fun createRaf(fileName: String): RandomAccessFile {
        return RandomAccessFile(File(tempDir, fileName), "rw")
    }

    @Test
    fun `writeHeader with max int dataLength writes correct size`() {
        val raf = createRaf("max_int.wav")
        val maxDataLength = Int.MAX_VALUE.toLong() // 2147483647
        WavHeaderWriter.writeHeader(raf, maxDataLength)
        raf.close()

        val bytes = File(tempDir, "max_int.wav").readBytes()

        // data sub-chunk size at offset 40 (LE int32)
        val dataChunkSize = ByteBuffer.wrap(bytes, 40, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(Int.MAX_VALUE, dataChunkSize)

        // RIFF chunk size at offset 4 (fileSize - 8 = 44 + MAX_VALUE - 8 = MAX_VALUE + 36)
        val riffChunkSize = ByteBuffer.wrap(bytes, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals((44L + Int.MAX_VALUE - 8).toInt(), riffChunkSize) // オーバーフローする
    }

    @Test
    fun `writeHeader with dataLength exceeding Int_MAX_VALUE throws exception`() {
        val raf = createRaf("overflow.wav")
        val overflowLength = Int.MAX_VALUE.toLong() + 1 // 2147483648L
        
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            WavHeaderWriter.writeHeader(raf, overflowLength)
        }
        raf.close()
    }

    @Test
    fun `writeHeader with zero dataLength writes zero`() {
        val raf = createRaf("zero.wav")
        WavHeaderWriter.writeHeader(raf, dataLength = 0)
        raf.close()

        val bytes = File(tempDir, "zero.wav").readBytes()
        val dataChunkSize = ByteBuffer.wrap(bytes, 40, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0, dataChunkSize)
    }

    @Test
    fun `writeHeader with very large dataLength throws exception`() {
        val raf = createRaf("huge.wav")
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            WavHeaderWriter.writeHeader(raf, dataLength = Long.MAX_VALUE)
        }
        raf.close()
    }
}
