package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 1ファイルWAV録音。PCMデータをメモリに蓄積し、stop()で一気に書き出す。
 * RandomAccessFileを使わないことで、エミュレータのfuse FS遅延問題を回避。
 */
class SimpleRecorder(
    private val outputDir: File,
    private val audioProvider: AudioProvider,
    private val coroutineScope: CoroutineScope
) {
    private val mutex = Mutex()
    private var recordingJob: Job? = null
    private var pcmBuffer: ByteArrayOutputStream? = null
    private var currentOutputFile: File? = null
    @Volatile var isRecording = false
        private set
    @Volatile var isPaused = false
        private set

    suspend fun start(): File {
        stopInternal()

        if (!audioProvider.start()) {
            throw IllegalStateException("AudioProvider.start() failed")
        }

        val fileName = "${System.nanoTime()}.wav"
        val file = File(outputDir, fileName)
        outputDir.mkdirs()

        mutex.withLock {
            isRecording = true
            isPaused = false
            pcmBuffer = ByteArrayOutputStream()
            currentOutputFile = file
        }

        recordingJob = coroutineScope.launch {
            try {
                val buffer = ShortArray(AudioConstants.READ_BUFFER)
                while (isRecording) {
                    if (isPaused) {
                        delay(50)
                        continue
                    }
                    val read = audioProvider.read(buffer, AudioConstants.READ_BUFFER)
                    if (read < 0) {
                        if (isPaused) { delay(50); continue }
                        break
                    }
                    if (read == 0) continue

                    mutex.withLock {
                        if (!isRecording || isPaused) return@withLock
                        val buf = pcmBuffer ?: return@withLock
                        val byteBuf = ByteArray(read * 2)
                        ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().put(buffer, 0, read)
                        buf.write(byteBuf)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop error", e)
            }
        }
        return file
    }

    suspend fun stop(): File? {
        stopInternal()

        val file: File?
        val pcmData: ByteArray?

        mutex.withLock {
            pcmData = pcmBuffer?.toByteArray()
            pcmBuffer = null
            file = currentOutputFile
        }

        audioProvider.release()

        if (file != null && pcmData != null) {
            writeWavFile(file, pcmData)
            Log.d(TAG, "stop(): saved ${file.absolutePath}, ${pcmData.size} bytes PCM")
        }

        return file
    }

    suspend fun pause() {
        mutex.withLock {
            if (!isRecording || isPaused) return
            isPaused = true
        }
        audioProvider.stop()
    }

    suspend fun resume() {
        mutex.withLock {
            if (!isRecording || !isPaused) return
            isPaused = false
        }
        if (!audioProvider.start()) {
            mutex.withLock { isPaused = true }
        }
    }

    private suspend fun stopInternal() {
        val jobToCancel = mutex.withLock {
            val wasRecording = isRecording
            isRecording = false
            isPaused = false
            if (wasRecording) audioProvider.stop()
            recordingJob
        }
        withTimeoutOrNull(3000L) {
            jobToCancel?.cancelAndJoin()
        }
    }

    companion object {
        private const val TAG = "SimpleRecorder"

        fun writeWavFile(
            file: File,
            pcmData: ByteArray,
            sampleRate: Int = AudioConstants.SAMPLE_RATE,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ) {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataLength = pcmData.size.toLong()
            val riffSize = 36 + dataLength

            FileOutputStream(file).use { fos ->
                fos.write("RIFF".toByteArray(Charsets.US_ASCII))
                fos.writeIntLE(riffSize.toInt())
                fos.write("WAVE".toByteArray(Charsets.US_ASCII))
                fos.write("fmt ".toByteArray(Charsets.US_ASCII))
                fos.writeIntLE(16)
                fos.writeShortLE(1) // PCM
                fos.writeShortLE(channels)
                fos.writeIntLE(sampleRate)
                fos.writeIntLE(byteRate)
                fos.writeShortLE(blockAlign)
                fos.writeShortLE(bitsPerSample)
                fos.write("data".toByteArray(Charsets.US_ASCII))
                fos.writeIntLE(dataLength.toInt())
                fos.write(pcmData)
                fos.fd.sync()
            }
        }

        private fun java.io.OutputStream.writeIntLE(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
            write((value shr 16) and 0xFF)
            write((value shr 24) and 0xFF)
        }

        private fun java.io.OutputStream.writeShortLE(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
        }
    }
}
