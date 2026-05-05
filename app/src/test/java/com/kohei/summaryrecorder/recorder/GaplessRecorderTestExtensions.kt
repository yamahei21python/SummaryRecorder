package com.kohei.summaryrecorder.recorder

import java.nio.ByteBuffer
import java.nio.ByteOrder

suspend fun GaplessRecorder.writeTestPcmData(data: ByteArray) {
    val raf = currentFile ?: openNewFile()
    val shortCount = data.size / 2
    val shorts = ShortArray(shortCount)
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
    writePcmData(raf, shorts, shortCount)

    if (currentBytesWritten >= chunkSizeBytes) {
        val bytes = currentBytesWritten
        val index = currentChunkIndex
        currentFile = null
        finalizeChunk(raf, bytes, index, isLast = false)
        currentChunkIndex++
        currentBytesWritten = 0
        openNewFile()
    }
}

suspend fun GaplessRecorder.stopForTest() {
    isRecording = false
    val raf = currentFile
    val bytes = currentBytesWritten
    val index = currentChunkIndex
    if (raf != null) {
        currentFile = null
        finalizeChunk(raf, bytes, index, isLast = true)
    }
}
