package com.kohei.summaryrecorder.audio

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DummyAudioProvider(
    inputStream: InputStream
) : AudioProvider {

    private val audioData: ByteArray = inputStream.readBytes()
    private var bais = ByteArrayInputStream(audioData)
    private var isActive = false

    override fun start(): Boolean {
        isActive = true
        return true
    }

    override fun read(buffer: ShortArray, size: Int): Int {
        if (!isActive) return -1
        
        val bytesToRead = size * 2
        val tempBuffer = ByteArray(bytesToRead)
        
        var readBytes = bais.read(tempBuffer)
        if (readBytes == -1) {
            // ループ
            bais = ByteArrayInputStream(audioData)
            readBytes = bais.read(tempBuffer)
        }
        
        if (readBytes <= 0) return -1

        val shortsRead = readBytes / 2
        ByteBuffer.wrap(tempBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buffer, 0, shortsRead)
        return shortsRead
    }

    override fun stop() { isActive = false }
    override fun release() { isActive = false }
}
