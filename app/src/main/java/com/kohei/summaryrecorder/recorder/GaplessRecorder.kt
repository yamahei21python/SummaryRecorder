package com.kohei.summaryrecorder.recorder

import com.kohei.summaryrecorder.domain.provider.AudioProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GaplessRecorder(
    private val outputDir: File,
    private val chunkSizeBytes: Long,
    private val onChunkComplete: (chunkIndex: Int, file: File) -> Unit,
    private val audioProvider: AudioProvider,
    private val coroutineScope: CoroutineScope
) {
    private val mutex = Mutex()
    private var recordingJob: Job? = null
    private var currentFile: RandomAccessFile? = null
    private var currentChunkIndex = 0
    private var currentBytesWritten = 0L
    private var isRecording = false
    private val writeBuffer = ByteBuffer.allocate(AudioConstants.READ_BUFFER * 2).order(ByteOrder.LITTLE_ENDIAN)

    fun start() {
        if (!audioProvider.start()) {
            throw IllegalStateException("AudioProvider.start() failed")
        }

        isRecording = true
        currentChunkIndex = 0
        currentBytesWritten = 0L

        recordingJob = coroutineScope.launch {
            mutex.withLock { openNewFile() }

            val buffer = ShortArray(AudioConstants.READ_BUFFER)
            while (isRecording) {
                val read = audioProvider.read(buffer, AudioConstants.READ_BUFFER)
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

    suspend fun stop() {
        isRecording = false

        audioProvider.stop()
        audioProvider.release()

        recordingJob?.cancel()
        recordingJob = null

        mutex.withLock {
            finalizeCurrentChunk()
        }
    }

    private fun openNewFile() {
        val file = File(outputDir, "chunk_${currentChunkIndex}.wav")
        currentFile = RandomAccessFile(file, "rw").also {
            WavHeaderWriter.writeDummyHeader(it)
        }
    }

    private fun writePcmData(buffer: ShortArray, readCount: Int) {
        val file = currentFile ?: return
        writeBuffer.clear()
        writeBuffer.asShortBuffer().put(buffer, 0, readCount)
        file.write(writeBuffer.array(), 0, readCount * 2)
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
