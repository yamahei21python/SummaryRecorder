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
        require(dataLength <= Int.MAX_VALUE) { "dataLength exceeds 2GB limit of standard WAV" }
        
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        // RIFF header structure:
        // "RIFF" (4) + TotalSize (4) + "WAVE" (4) + "fmt " (4) + FmtSize (4) + 
        // AudioFormat (2) + Channels (2) + SampleRate (4) + ByteRate (4) + 
        // BlockAlign (2) + BitsPerSample (2) + "data" (4) + DataSize (4)
        // RIFF chunk size = 4 ("WAVE") + 24 (fmt chunk) + 8 (data header) + DataSize
        // So RIFF size field = 36 + DataSize
        val riffSize = 36 + dataLength

        file.seek(0)

        // RIFF header
        file.writeAsciiBytes("RIFF")
        file.writeIntLE(riffSize.toInt())
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
        write(s.toByteArray(Charsets.US_ASCII))
    }
}
