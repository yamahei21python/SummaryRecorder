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
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val fileSize = HEADER_SIZE + dataLength

        file.seek(0)

        // RIFF header
        file.writeBytes("RIFF")
        file.writeIntLE((fileSize - 8).toInt())
        file.writeBytes("WAVE")

        // fmt sub-chunk
        file.writeBytes("fmt ")
        file.writeIntLE(16)
        file.writeShortLE(1) // PCM = 1
        file.writeShortLE(channels)
        file.writeIntLE(sampleRate)
        file.writeIntLE(byteRate)
        file.writeShortLE(blockAlign)
        file.writeShortLE(bitsPerSample)

        // data sub-chunk
        file.writeBytes("data")
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

    private fun RandomAccessFile.writeShortLE(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value shr 8) and 0xFF)
    }

    private fun RandomAccessFile.writeBytes(s: String) {
        s.toByteArray(Charsets.US_ASCII).forEach { writeByte(it.toInt()) }
    }
}
