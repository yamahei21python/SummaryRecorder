package com.kohei.summaryrecorder.audio

import com.kohei.summaryrecorder.domain.provider.AudioProvider
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DummyAudioProvider(
    inputStream: InputStream,
    private val readDelayMs: Long = 0L,
    private val loop: Boolean = true
) : AudioProvider {

    private val bufferedStream = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
    private var isActive = false
    private var headerSkipped = false
    private var pcmStart = -1L

    override fun start(): Boolean {
        isActive = true
        headerSkipped = false
        pcmStart = -1L
        return true
    }

    override fun read(buffer: ShortArray, size: Int): Int {
        if (!isActive) return -1
        ensureHeaderSkipped()

        val byteBuf = ByteArray(size * 2)
        var read = bufferedStream.read(byteBuf, 0, byteBuf.size)

        if (read <= 0 && loop) {
            bufferedStream.reset()
            pcmStart = -1L
            ensureHeaderSkipped()
            read = bufferedStream.read(byteBuf, 0, byteBuf.size)
        }
        if (read <= 0) return -1

        if (readDelayMs > 0) Thread.sleep(readDelayMs)

        ByteBuffer.wrap(byteBuf, 0, read)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(buffer, 0, read / 2)
        return read / 2
    }

    private fun ensureHeaderSkipped() {
        if (!headerSkipped) {
            if (pcmStart < 0) {
                bufferedStream.mark(Int.MAX_VALUE)
                bufferedStream.skip(44)
                pcmStart = 44
            }
            headerSkipped = true
        }
    }

    override fun stop() { isActive = false }

    override fun release() {
        isActive = false
        bufferedStream.close()
    }
}
