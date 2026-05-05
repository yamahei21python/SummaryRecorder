package com.kohei.summaryrecorder.recorder

import java.io.RandomAccessFile

/**
 * WAVヘッダー生成・上書きユーティリティ。
 * ステートレスなobjectシングルトン。
 *
 * RIFF/WAVE/fmt/data 44byte固定ヘッダー。
 * Little-Endian手動書込（RandomAccessFileはBE-onlyのため）。
 */
object WavHeaderWriter {

    const val HEADER_SIZE = 44

    /**
     * WAVヘッダーを書込む。
     * 呼出前にファイルポインタは先頭にあること。
     * 書込後、ポインタはHEADER_SIZEの位置にある。
     */
    fun writeHeader(
        file: RandomAccessFile,
        dataLength: Long,
        sampleRate: Int = AudioConstants.SAMPLE_RATE,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val fileSize = HEADER_SIZE + dataLength

        file.seek(0)

        // RIFF header
        file.writeAsciiBytes("RIFF")
        file.writeIntLE((fileSize - 8).toInt())
        file.writeAsciiBytes("WAVE")

        // fmt sub-chunk
        file.writeAsciiBytes("fmt ")
        file.writeIntLE(16)
        file.writeShortLE(1.toShort()) // PCM = 1
        file.writeShortLE(channels.toShort())
        file.writeIntLE(sampleRate)
        file.writeIntLE(byteRate)
        file.writeShortLE(blockAlign.toShort())
        file.writeShortLE(bitsPerSample.toShort())

        // data sub-chunk
        file.writeAsciiBytes("data")
        file.writeIntLE(dataLength.toInt())
    }

    /**
     * ダミーヘッダー（dataLength=0）を書込む。
     * 録音開始時に使用。終了時に seek(0) で上書き。
     */
    fun writeDummyHeader(file: RandomAccessFile) {
        writeHeader(file, dataLength = 0)
    }

    // ---- Little-Endian helpers ----

    private fun RandomAccessFile.writeIntLE(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value shr 8) and 0xFF)
        writeByte((value shr 16) and 0xFF)
        writeByte((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Short) {
        writeByte(value.toInt() and 0xFF)
        writeByte((value.toInt() shr 8) and 0xFF)
    }

    private fun RandomAccessFile.writeAsciiBytes(s: String) {
        s.toByteArray(Charsets.US_ASCII).forEach { writeByte(it.toInt()) }
    }
}
