package com.kohei.summaryrecorder.recorder

import androidx.annotation.VisibleForTesting
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

class GaplessRecorder(
    private val outputDir: File,
    @VisibleForTesting internal val chunkSizeBytes: Long,
    private val onChunkComplete: (chunkIndex: Int, file: File) -> Unit,
    private val audioProvider: AudioProvider,
    private val coroutineScope: CoroutineScope
) {
    private val mutex = Mutex()
    private var recordingJob: Job? = null
    @VisibleForTesting internal var currentFile: RandomAccessFile? = null
    @VisibleForTesting internal var currentChunkIndex = 0
    @VisibleForTesting internal var currentBytesWritten = 0
    @Volatile @VisibleForTesting internal var isRecording = false

    suspend fun start() {
        recordingJob?.cancelAndJoin()
        if (!audioProvider.start()) {
            throw IllegalStateException("AudioProvider.start() failed")
        }

        isRecording = true
        currentChunkIndex = 0
        currentBytesWritten = 0

        recordingJob = coroutineScope.launch {
            mutex.withLock { openNewFile() }

            val buffer = ShortArray(AudioConstants.READ_BUFFER)
            while (isRecording) {
                val read = audioProvider.read(buffer, AudioConstants.READ_BUFFER)
                if (read < 0) break
                if (read == 0) continue

                mutex.withLock {
                    if (!isRecording) return@withLock
                    writePcmData(buffer, read)

                    if (currentBytesWritten >= chunkSizeBytes) {
                        finalizeCurrentChunk()
                        currentChunkIndex++
                        currentBytesWritten = 0
                        openNewFile()
                    }
                }
            }
        }
    }

    suspend fun stop() {
        isRecording = false

        audioProvider.stop()

        recordingJob?.cancelAndJoin()
        recordingJob = null

        audioProvider.release()

        mutex.withLock {
            finalizeCurrentChunk()
        }
    }

    @VisibleForTesting
    internal fun openNewFile() {
        val file = File(outputDir, "chunk_${currentChunkIndex}.wav")
        currentFile = RandomAccessFile(file, "rw").also {
            WavHeaderWriter.writeDummyHeader(it)
        }
    }

    @VisibleForTesting
    internal fun writePcmData(buffer: ShortArray, readCount: Int) {
        val file = currentFile ?: return
        val byteBuf = ByteArray(readCount * 2)
        ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, readCount)
        file.write(byteBuf)
        currentBytesWritten += readCount * 2
    }

    @VisibleForTesting
    internal fun finalizeCurrentChunk() {
        currentFile?.let { raf ->
            val dataLength = currentBytesWritten.toLong()
            WavHeaderWriter.writeHeader(raf, dataLength)
            raf.close()
            currentFile = null

            val file = File(outputDir, "chunk_${currentChunkIndex}.wav")
            if (dataLength > 0) {
                if (file.exists()) {
                    onChunkComplete(currentChunkIndex, file)
                }
            } else {
                if (file.exists()) file.delete()
            }
        }
    }
}
