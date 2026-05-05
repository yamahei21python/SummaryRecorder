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
    private val onChunkComplete: suspend (chunkIndex: Int, file: File, isLast: Boolean) -> Unit,
    private val audioProvider: AudioProvider,
    private val coroutineScope: CoroutineScope
) {
    private val mutex = Mutex()
    private var recordingJob: Job? = null
    @VisibleForTesting internal var currentFile: RandomAccessFile? = null
    @VisibleForTesting internal var currentChunkIndex = 0
    @VisibleForTesting internal var currentBytesWritten = 0
    @Volatile @VisibleForTesting internal var isRecording = false
    @Volatile @VisibleForTesting internal var isPaused = false
    @VisibleForTesting internal var currentFileName: String = ""

    suspend fun start() {
        stopInternal()

        if (!audioProvider.start()) {
            throw IllegalStateException("AudioProvider.start() failed")
        }

        mutex.withLock {
            isRecording = true
            isPaused = false
            currentChunkIndex = 0
            currentBytesWritten = 0

            recordingJob = coroutineScope.launch {
                try {
                    // 最初のファイルオープン
                    mutex.withLock { openNewFile() }
                    
                    val buffer = ShortArray(AudioConstants.READ_BUFFER)
                    while (isRecording) {
                        if (isPaused) {
                            // 一時停止中はreadせずビジーウェイト
                            kotlinx.coroutines.delay(50)
                            continue
                        }
                        
                        val read = audioProvider.read(buffer, AudioConstants.READ_BUFFER)
                        if (read < 0) break
                        if (read == 0) continue

                        var shouldSplit = false
                        var fileToFinalize: RandomAccessFile? = null
                        var fileNameToFinalize: String = ""
                        var bytesToFinalize = 0
                        var indexToFinalize = 0

                        mutex.withLock {
                            if (!isRecording || isPaused) return@withLock
                            
                            val file = currentFile ?: return@withLock
                            writePcmData(file, buffer, read)
                            
                            if (currentBytesWritten >= chunkSizeBytes) {
                                shouldSplit = true
                                fileToFinalize = currentFile
                                fileNameToFinalize = currentFileName
                                bytesToFinalize = currentBytesWritten
                                indexToFinalize = currentChunkIndex
                                
                                currentFile = null
                                currentChunkIndex++
                                currentBytesWritten = 0
                                openNewFile()
                            }
                        }

                        if (shouldSplit) {
                            finalizeChunk(fileToFinalize, fileNameToFinalize, bytesToFinalize, indexToFinalize, isLast = false)
                        }
                    }
                } finally {
                    val fileToFinalize: RandomAccessFile?
                    val fileNameToFinalize: String
                    val bytesToFinalize: Int
                    val indexToFinalize: Int
                    
                    mutex.withLock {
                        fileToFinalize = currentFile
                        fileNameToFinalize = currentFileName
                        bytesToFinalize = currentBytesWritten
                        indexToFinalize = currentChunkIndex
                        currentFile = null
                    }
                    
                    finalizeChunk(fileToFinalize, fileNameToFinalize, bytesToFinalize, indexToFinalize, isLast = true)
                }
            }
        }
    }

    suspend fun stop() {
        stopInternal()
        audioProvider.release()
    }

    /**
     * 一時停止: AudioProviderを停止し、isPaused=trueに設定。
     * isRecording=trueは維持（録音セッションは継続）。
     */
    suspend fun pause() {
        mutex.withLock {
            if (!isRecording || isPaused) return
            isPaused = true
        }
        audioProvider.stop()
    }

    /**
     * 再開: AudioProviderを再開し、isPaused=falseに設定。
     */
    suspend fun resume() {
        mutex.withLock {
            if (!isRecording || !isPaused) return
            isPaused = false
        }
        audioProvider.start()
    }

    private suspend fun stopInternal() {
        val jobToCancel = mutex.withLock {
            val wasRecording = isRecording
            isRecording = false
            isPaused = false
            if (wasRecording) {
                audioProvider.stop()
            }
            val job = recordingJob
            recordingJob = null
            job
        }
        jobToCancel?.cancelAndJoin()
    }

    @VisibleForTesting
    internal fun openNewFile(): RandomAccessFile {
        val fileName = "chunk_${System.currentTimeMillis() % 1_000_000}_${currentChunkIndex}.wav"
        val file = File(outputDir, fileName)
        val raf = RandomAccessFile(file, "rw").also {
            WavHeaderWriter.writeDummyHeader(it)
        }
        currentFile = raf
        currentFileName = fileName
        return raf
    }

    @VisibleForTesting
    internal fun writePcmData(raf: RandomAccessFile, buffer: ShortArray, readCount: Int) {
        val byteBuf = ByteArray(readCount * 2)
        ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, readCount)
        raf.write(byteBuf)
        currentBytesWritten += readCount * 2
    }

    @VisibleForTesting
    internal suspend fun finalizeChunk(raf: RandomAccessFile?, fileName: String, bytesWritten: Int, index: Int, isLast: Boolean) {
        raf?.let {
            val dataLength = bytesWritten.toLong()
            WavHeaderWriter.writeHeader(it, dataLength)
            it.close()

            val file = File(outputDir, fileName)
            if (dataLength > 0 || isLast) {
                if (file.exists()) {
                    onChunkComplete(index, file, isLast)
                }
            } else {
                if (file.exists()) file.delete()
            }
        }
    }
}
