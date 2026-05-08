package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SimpleRecorder(
    private val outputDir: File,
    private val audioProvider: AudioProvider,
    private val coroutineScope: CoroutineScope
) {
    private val mutex = Mutex()
    private var recordingJob: Job? = null
    private var currentFile: RandomAccessFile? = null
    private var currentOutputFile: File? = null
    @Volatile var isRecording = false
        private set
    @Volatile var isPaused = false
        private set
    private var currentBytesWritten = 0L

    suspend fun start(): File {
        stopInternal()

        if (!audioProvider.start()) {
            throw IllegalStateException("AudioProvider.start() failed")
        }

        val fileName = "${System.nanoTime()}.wav"
        val file = File(outputDir, fileName)
        outputDir.mkdirs()
        val raf = RandomAccessFile(file, "rw")
        writeWavHeader(raf, dataLength = 0)

        mutex.withLock {
            isRecording = true
            isPaused = false
            currentFile = raf
            currentOutputFile = file
            currentBytesWritten = 0
        }

        recordingJob = coroutineScope.launch {
            try {
                val buffer = ShortArray(AudioConstants.READ_BUFFER)
                while (isRecording) {
                    if (isPaused) {
                        kotlinx.coroutines.delay(50)
                        continue
                    }
                    val read = audioProvider.read(buffer, AudioConstants.READ_BUFFER)
                    if (read < 0) {
                        if (isPaused) { kotlinx.coroutines.delay(50); continue }
                        break
                    }
                    if (read == 0) continue

                    mutex.withLock {
                        if (!isRecording || isPaused) return@withLock
                        val fileRef = currentFile ?: return@withLock
                        val byteBuf = ByteArray(read * 2)
                        ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read)
                        fileRef.write(byteBuf)
                        currentBytesWritten += read * 2
                    }
                }
            } finally {
                mutex.withLock {
                    currentFile?.let { raf ->
                        writeWavHeader(raf, currentBytesWritten)
                        raf.close()
                    }
                    currentFile = null
                }
            }
        }
        return file
    }

    suspend fun stop(): File? {
        val resultFile: File?
        stopInternal()
        resultFile = currentOutputFile
        audioProvider.release()
        return resultFile
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
        jobToCancel?.cancelAndJoin()
    }

    companion object {
        private const val HEADER_SIZE = 44

        fun writeWavHeader(
            file: RandomAccessFile,
            dataLength: Long,
            sampleRate: Int = AudioConstants.SAMPLE_RATE,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ) {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val riffSize = 36 + dataLength

            file.seek(0)
            file.write("RIFF".toByteArray(Charsets.US_ASCII))
            file.writeIntLE(riffSize.toInt())
            file.write("WAVE".toByteArray(Charsets.US_ASCII))
            file.write("fmt ".toByteArray(Charsets.US_ASCII))
            file.writeIntLE(16)
            file.writeShortLE(1) // PCM
            file.writeShortLE(channels)
            file.writeIntLE(sampleRate)
            file.writeIntLE(byteRate)
            file.writeShortLE(blockAlign)
            file.writeShortLE(bitsPerSample)
            file.write("data".toByteArray(Charsets.US_ASCII))
            file.writeIntLE(dataLength.toInt())
        }

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
    }
}
