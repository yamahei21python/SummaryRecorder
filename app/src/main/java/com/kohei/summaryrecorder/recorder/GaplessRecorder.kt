package com.kohei.summaryrecorder.recorder

import androidx.annotation.VisibleForTesting
import com.kohei.summaryrecorder.audio.AudioProvider
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.RealAudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * @param audioProvider 音源（デフォルト=RealAudioProvider、テスト/Debug時=DummyAudioProvider）
 */
class GaplessRecorder(
    private val outputDir: File,
    private val chunkSizeBytes: Long = DebugConfig.chunkSizeBytes,
    private val onChunkComplete: (chunkIndex: Int, file: File) -> Unit,
    private val audioProvider: AudioProvider? = null
) {
    // AudioConfig
    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
        const val READ_BUFFER = 4096
    }

    private val mutex = Mutex()
    private var provider: AudioProvider? = null
    private var currentFile: RandomAccessFile? = null
    private var currentChunkIndex = 0
    private var currentBytesWritten = 0L
    private var isRecording = false
    private val recordingScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )

    // ===== 公開API =====

    fun start() {
        val provider = audioProvider ?: RealAudioProvider(
            sampleRate = SAMPLE_RATE,
            bufferSize = READ_BUFFER
        )
        if (!provider.start()) {
            throw IllegalStateException("AudioProvider.start() failed")
        }
        this.provider = provider

        isRecording = true
        currentChunkIndex = 0
        currentBytesWritten = 0L

        recordingScope.launch {
            mutex.withLock { openNewFile() }

            val buffer = ShortArray(READ_BUFFER)
            while (isRecording) {
                val read = provider.read(buffer, READ_BUFFER)
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

        recordingScope.coroutineContext.cancelChildren()

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

    // ===== テスト用API（@VisibleForTesting相当） =====

    /**
     * テスト用: バイト配列をPCMデータとして書込む。
     * AudioProviderを使わずにファイル書込みロジックを検証する。
     */
    @VisibleForTesting
    fun writeTestPcmData(data: ByteArray) {
        if (currentFile == null) {
            openNewFile()
        }
        val shortCount = data.size / 2
        val shorts = ShortArray(shortCount)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        writePcmData(shorts, shortCount)

        if (currentBytesWritten >= chunkSizeBytes) {
            finalizeCurrentChunk()
            currentChunkIndex++
            currentBytesWritten = 0L
            openNewFile()
        }
    }

    /**
     * テスト用: 現在のチャンクを確定して停止。
     * AudioProviderへの依存なし。非suspend（runBlocking内でmutex使用）。
     */
    @VisibleForTesting
    fun stopForTest() {
        isRecording = false
        if (currentFile != null) {
            finalizeCurrentChunk()
        }
    }
}
