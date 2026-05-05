package com.kohei.summaryrecorder.recorder

import java.nio.ByteBuffer
import java.nio.ByteOrder

suspend fun GaplessRecorder.writeTestPcmData(data: ByteArray) {
    if (currentFile == null) {
        openNewFile()
    }
    val shortCount = data.size / 2
    val shorts = ShortArray(shortCount)
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
    writePcmData(shorts, shortCount)

    if (currentBytesWritten >= chunkSizeBytes) {
        finalizeCurrentChunk(isLast = false)
        currentChunkIndex++
        currentBytesWritten = 0
        openNewFile()
    }
}

suspend fun GaplessRecorder.stopForTest() {
    isRecording = false
    if (currentFile != null) {
        finalizeCurrentChunk(isLast = true)
    }
}
