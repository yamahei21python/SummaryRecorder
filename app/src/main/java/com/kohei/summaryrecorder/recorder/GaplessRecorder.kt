package com.kohei.summaryrecorder.recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ギャップレス連続録音エンジン。
 *
 * PCM 16kHz/Mono/16bit を AudioRecord で連続読込し、
 * 指定サイズ到達時に自動でファイル分割する。
 * Mutex排他でデータ欠落ゼロを保証。
 *
 * @param outputDir チャンク出力ディレクトリ
 * @param chunkSizeBytes チャンク上限（デフォルト19MB ≒ 10分）
 * @param onChunkComplete チャンク確定時コールバック（インデックス, ファイル）
 */
class GaplessRecorder(
    private val outputDir: File,
    private val chunkSizeBytes: Long = 19L * 1024 * 1024,
    private val onChunkComplete: (chunkIndex: Int, file: File) -> Unit
) {
    // AudioConfig
    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val mutex = Mutex()
    private var audioRecord: AudioRecord? = null
    private var currentFile: RandomAccessFile? = null
    private var currentChunkIndex = 0
    private var currentBytesWritten = 0L
    private var isRecording = false
    private val recordingScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )

    // ===== 公開API =====

    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        require(minBufferSize != AudioRecord.ERROR_BAD_VALUE) {
            "AudioRecord: unsupported audio config"
        }
        val bufferSize = maxOf(minBufferSize, 4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        ).also { it.startRecording() }

        isRecording = true
        currentChunkIndex = 0
        currentBytesWritten = 0L

        recordingScope.launch {
            mutex.withLock { openNewFile() }

            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: break
                if (read <= 0) continue

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
     * 停止フロー:
     * 1. isRecording=false → ループ脱出フラグ
     * 2. audioRecord.release() → read()が即座に終了
     * 3. cancelChildren() → 残存コルーチン破棄
     * 4. mutex.withLock { finalizeCurrentChunk() } → 最終チャンク安全確定
     */
    fun stop() {
        isRecording = false

        audioRecord?.apply {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        audioRecord = null

        recordingScope.coroutineContext.cancelChildren()

        runBlocking {
            mutex.withLock {
                finalizeCurrentChunk()
            }
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
     * AudioRecordを使わずにファイル書込みロジックを検証する。
     */
    fun writeTestPcmData(data: ByteArray) {
        // ファイル未オープンなら新規作成
        if (currentFile == null) {
            openNewFile()
        }
        // Short配列に変換して書込み
        // dataが奇数長の場合は最後の1byteは無視
        val shortCount = data.size / 2
        val shorts = ShortArray(shortCount)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        writePcmData(shorts, shortCount)

        // チャンクサイズ到達 → 確定→新ファイル
        if (currentBytesWritten >= chunkSizeBytes) {
            finalizeCurrentChunk()
            currentChunkIndex++
            currentBytesWritten = 0L
            openNewFile()
        }
    }

    /**
     * テスト用: 現在のチャンクを確定して停止。
     * AudioRecordへの依存なし。
     */
    fun stopForTest() {
        isRecording = false
        // テスト時はcurrentFileを直接確定
        if (currentFile != null) {
            finalizeCurrentChunk()
        }
    }
}
