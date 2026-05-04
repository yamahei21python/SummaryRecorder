package com.kohei.summaryrecorder.recorder

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals

/**
 * WavHeaderWriter: seek(0)でデータサイズ上書き検証
 */
class WavHeaderSeekTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `writeDummy then writeHeader overwrites dataLength`() {
        val file = File(tempDir, "seek_test.wav")
        val raf = RandomAccessFile(file, "rw")

        // 1) ダミーヘッダー（dataLength=0）
        WavHeaderWriter.writeDummyHeader(raf)

        // 2) PCMデータ書込み（1000 bytes模擬）
        raf.write(ByteArray(1000))

        // 3) seek(0)でヘッダー上書き
        val actualDataLength = raf.length() - 44
        WavHeaderWriter.writeHeader(raf, dataLength = actualDataLength)
        raf.close()

        // 検証: data sub-chunk size = 1000
        val bytes = file.readBytes()
        val dataChunkSize = ByteBuffer.wrap(bytes, 40, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(1000, dataChunkSize)

        // RIFF chunk size = 44 + 1000 - 8 = 1036
        val chunkSize = ByteBuffer.wrap(bytes, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(1036, chunkSize)

        // ファイルサイズ = 44 + 1000 = 1044
        assertEquals(1044, file.length())
    }

    @Test
    fun `multiple seek overwrites preserve PCM data`() {
        val file = File(tempDir, "multi_seek.wav")
        val raf = RandomAccessFile(file, "rw")

        // ダミーヘッダー
        WavHeaderWriter.writeDummyHeader(raf)

        // PCMデータ書込み
        val pcmData = ByteArray(500) { it.toByte() }
        raf.write(pcmData)

        // ヘッダー上書き
        WavHeaderWriter.writeHeader(raf, dataLength = 500)
        raf.close()

        // PCMデータが壊れていないか検証
        val bytes = file.readBytes()
        for (i in 0 until 500) {
            assertEquals((i and 0xFF).toByte(), bytes[44 + i])
        }
    }

    @Test
    fun `writeHeader with non-default audio params`() {
        val file = File(tempDir, "custom_params.wav")
        val raf = RandomAccessFile(file, "rw")

        WavHeaderWriter.writeHeader(
            raf,
            dataLength = 1000,
            sampleRate = 44100,
            channels = 2,
            bitsPerSample = 24
        )
        raf.close()

        val bytes = file.readBytes()
        val numChannels = ByteBuffer.wrap(bytes, 22, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short
        val sampleRate = ByteBuffer.wrap(bytes, 24, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        val bitsPerSample = ByteBuffer.wrap(bytes, 34, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short
        val byteRate = ByteBuffer.wrap(bytes, 28, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int

        assertEquals(2, numChannels.toInt())
        assertEquals(44100, sampleRate)
        assertEquals(24, bitsPerSample.toInt())
        assertEquals(44100 * 2 * 24 / 8, byteRate) // byteRate = sampleRate * channels * bitsPerSample / 8
    }
}
