package com.kohei.summaryrecorder.recorder

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun <T> GaplessRecorder.getPrivate(name: String, returnType: Class<T>): T {
    val field = GaplessRecorder::class.java.getDeclaredField(name)
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as T
}

private fun GaplessRecorder.setPrivate(name: String, value: Any?) {
    val field = GaplessRecorder::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
}

private fun GaplessRecorder.invokeWritePcmData(buffer: ShortArray, readCount: Int) {
    val method = GaplessRecorder::class.java.getDeclaredMethod(
        "writePcmData", ShortArray::class.java, Int::class.javaPrimitiveType
    )
    method.isAccessible = true
    method.invoke(this, buffer, readCount)
}

private fun GaplessRecorder.invokeOpenNewFile() {
    val method = GaplessRecorder::class.java.getDeclaredMethod("openNewFile")
    method.isAccessible = true
    method.invoke(this)
}

private fun GaplessRecorder.invokeFinalizeCurrentChunk() {
    val method = GaplessRecorder::class.java.getDeclaredMethod("finalizeCurrentChunk")
    method.isAccessible = true
    method.invoke(this)
}

fun GaplessRecorder.writeTestPcmData(data: ByteArray) {
    val currentFile = getPrivate("currentFile", RandomAccessFile::class.java)
    if (currentFile == null) {
        invokeOpenNewFile()
    }
    val shortCount = data.size / 2
    val shorts = ShortArray(shortCount)
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
    invokeWritePcmData(shorts, shortCount)

    val currentBytesWritten = getPrivate("currentBytesWritten", Long::class.javaPrimitiveType!!)
    val chunkSizeBytes = getPrivate("chunkSizeBytes", Long::class.javaPrimitiveType!!)

    if (currentBytesWritten >= chunkSizeBytes) {
        invokeFinalizeCurrentChunk()
        val currentChunkIndex = getPrivate("currentChunkIndex", Int::class.javaPrimitiveType!!)
        setPrivate("currentChunkIndex", currentChunkIndex + 1)
        setPrivate("currentBytesWritten", 0L)
        invokeOpenNewFile()
    }
}

fun GaplessRecorder.stopForTest() {
    setPrivate("isRecording", false)
    val currentFile = getPrivate("currentFile", RandomAccessFile::class.java)
    if (currentFile != null) {
        invokeFinalizeCurrentChunk()
    }
}
