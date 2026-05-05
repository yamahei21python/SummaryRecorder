package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.provider.AudioProvider
import com.kohei.summaryrecorder.audio.DebugConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ギャップレス連続録音エンジン。
 *
 * PCM 16kHz/Mono/16bit を AudioProvider 経由で連続読込し、
 * 指定サイズ到達時に自動でファイル分割する。
 * Mutex排他でデータ欠落ゼロを保証。
 *
 * @param outputDir チャンク出力ディレクトリ
 * @param chunkSizeBytes チャンク上限（デフォルト19MB ≒ 10分、DebugConfigで切替）
 * @param onChunkComplete チャンク確定時コールバック（インデックス, ファイル）
 * @param audioProvider 音源（non-null、必須）
 * @param coroutineScope 外部注入のコルーチンスコープ（orphan防止）
 */
class GaplessRecorder(
    private val outputDir: File,
    private val chunkSizeBytes: Long = DebugConfig.chunkSizeBytes,
    private val onChunkComplete: (chunkIndex: Int, file: File) -> Unit,
    private val audioProvider: AudioProvider,
    private val coroutineScope: CoroutineScope
) {
    private val mutex = Mutex()
    private var provider: AudioProvider? = null
    private var currentFile: RandomAccessFile? = null
    private var currentChunkIndex = 0
    private var currentBytesWritten = 0L
    private var isRecording = false

    // ===== 公開API =====

    fun start() {
        if (!audioProvider.start()) {
            throw IllegalStateException("AudioProvider.start() failed")
        }
        provider = audioProvider

        isRecording = true
        currentChunkIndex = 0
        currentBytesWritten = 0L

        coroutineScope.launch {
            mutex.withLock { openNewFile() }

            val buffer = ShortArray(AudioConstants.READ_BUFFER)
            while (isRecording) {
                val read = provider.read(buffer, AudioConstants.READ_BUFFER)
                if (read <= 0) break

                mutex.withLock {
                    if (!isRecording) return@withLock
                    writePcmData(buffer, read)

                    if (currentBytesWritten >= chunkSizeBytes) {
                        finalizeCurrentChunk()
                        currentChunkIndex++
                        currentBytesWritten = 0L
                        openNewFile()
                    }
                }
            }
        }
    }

    /**
     * 停止（suspend版）。
     * runBlockingを使わず、呼び出し元のcoroutine contextで実行。
     *
     * フロー:
     * 1. isRecording=false → ループ脱出フラグ
     * 2. provider.release() → read()が即座に終了
     * 3. cancelChildren() → 残存コルーチン破棄
     * 4. mutex.withLock { finalizeCurrentChunk() } → 最終チャンク安全確定
     */
    suspend fun stop() {
        isRecording = false

        provider?.apply {
            stop()
            release()
        }
        provider = null

        coroutineScope.coroutineContext.cancelChildren()

        mutex.withLock {
            finalizeCurrentChunk()
        }
    }

    // ===== 内部メソッド =====

    private fun openNewFile() {
        val file = File(outputDir, "chunk_${currentChunkIndex}.wav")
        currentFile = RandomAccessFile(file, "rw").also {
            WavHeaderWriter.writeDummyHeader(it)
        }
    }

    private fun writePcmData(buffer: ShortArray, readCount: Int) {
        val byteBuffer = ByteBuffer.allocate(readCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asShortBuffer().put(buffer, 0, readCount)
        currentFile!!.write(byteBuffer.array())
        currentBytesWritten += readCount * 2L
    }

    private fun finalizeCurrentChunk() {
        currentFile?.let { raf ->
            val dataLength = currentBytesWritten
            WavHeaderWriter.writeHeader(raf, dataLength)
            raf.close()
            currentFile = null

            val file = File(outputDir, "chunk_${currentChunkIndex}.wav")
            if (file.exists() && dataLength > 0) {
                onChunkComplete(currentChunkIndex, file)
            }
        }
    }
}
