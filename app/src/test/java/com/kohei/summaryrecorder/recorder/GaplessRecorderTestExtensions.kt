package com.kohei.summaryrecorder.recorder

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun GaplessRecorder.writeTestPcmData(data: ByteArray) {
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
        currentBytesWritten = 0
        openNewFile()
    }
}

fun GaplessRecorder.stopForTest() {
    isRecording = false
    if (currentFile != null) {
        finalizeCurrentChunk()
    }
}
