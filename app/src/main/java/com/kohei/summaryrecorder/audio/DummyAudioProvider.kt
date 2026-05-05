package com.kohei.summaryrecorder.audio

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DummyAudioProvider(
    inputStream: InputStream,
    private val readDelayMs: Long = 0,
    private val loop: Boolean = true
) : AudioProvider {

    private val audioData: ByteArray
    private var bais: ByteArrayInputStream
    private var isActive = false

    init {
        val allBytes = inputStream.readBytes()
        // WAVヘッダー(44bytes)をスキップして保持
        audioData = if (allBytes.size >= 44) {
            allBytes.copyOfRange(44, allBytes.size)
        } else {
            allBytes
        }
        bais = ByteArrayInputStream(audioData)
    }

    override fun start(): Boolean {
        isActive = true
        return true
    }

    override fun read(buffer: ShortArray, size: Int): Int {
        if (!isActive) return -1
        
        if (readDelayMs > 0) {
            Thread.sleep(readDelayMs)
        }

        val bytesToRead = size * 2
        val tempBuffer = ByteArray(bytesToRead)
        
        var readBytes = bais.read(tempBuffer)
        if (readBytes <= 0 && loop) {
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
