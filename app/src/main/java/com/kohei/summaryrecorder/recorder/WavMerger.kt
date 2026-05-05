package com.kohei.summaryrecorder.recorder

import java.io.File
import java.io.RandomAccessFile

/**
 * 複数WAVチャンクを1ファイルに結合。
 * - 各チャンクのWAVヘッダー(44byte)をスキップしPCMのみ結合
 * - 1ファイルの場合はコピー（ショートサーキット）
 * - 結合後、chunkFilesを削除
 *
 * @return 再生時間(ms)
 */
object WavMerger {

    private const val WAV_HEADER_SIZE = 44
    private const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8 * CHANNELS

    fun merge(chunkFiles: List<File>, outputFile: File): Long {
        require(chunkFiles.isNotEmpty()) { "chunkFiles must not be empty" }

        // ショートサーキット: 1ファイルならそのままコピー
        if (chunkFiles.size == 1) {
            chunkFiles[0].copyTo(outputFile, overwrite = true)
            chunkFiles[0].delete()
            return calcDuration(outputFile)
        }

        // PCMサイズ計算
        var totalPcmBytes = 0L
        for (chunk in chunkFiles) {
            val pcmSize = chunk.length() - WAV_HEADER_SIZE
            if (pcmSize > 0) totalPcmBytes += pcmSize
        }

        // 結合ファイル出力
        RandomAccessFile(outputFile, "rw").use { raf ->
            WavHeaderWriter.writeDummyHeader(raf) // 先にダミーヘッダー
            for (chunk in chunkFiles) {
                val pcmSize = chunk.length() - WAV_HEADER_SIZE
                if (pcmSize <= 0) continue
                chunk.inputStream().buffered().use { input ->
                    input.skip(WAV_HEADER_SIZE.toLong())
                    val buffer = ByteArray(8192)
                    var remaining = pcmSize
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            // ヘッダーを正しいサイズで上書き
            raf.seek(0)
            WavHeaderWriter.writeHeader(raf, totalPcmBytes)
        }

        // チャンクファイル削除
        chunkFiles.forEach { it.delete() }

        return calcDuration(outputFile)
    }

    private fun calcDuration(file: File): Long {
        val pcmBytes = file.length() - WAV_HEADER_SIZE
        if (pcmBytes <= 0) return 0L
        val samples = pcmBytes / BYTES_PER_SAMPLE
        return samples * 1000L / SAMPLE_RATE
    }
}
