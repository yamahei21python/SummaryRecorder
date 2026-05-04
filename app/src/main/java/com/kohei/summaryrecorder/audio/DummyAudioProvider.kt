package com.kohei.summaryrecorder.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * E2E用: assets内WAVをループ読込、マイク入力のフリをする。
 * WAVヘッダ(44byte)スキップ後、PCM16bitデータを順次返す。
 * EOF到達時は先頭からループ（loop=true）。
 */
class DummyAudioProvider(
    private val inputStream: InputStream,
    /** 1回のread()間の遅延(ms)。0=即時全返却 */
    private val readDelayMs: Long = 0L,
    /** EOF到達時、先頭からループ */
    private val loop: Boolean = true
) : AudioProvider {

    private var isActive = false
    private var headerSkipped = false
    /** 初回start()時のstream位置を保存 */
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
        var read = inputStream.read(byteBuf, 0, byteBuf.size)

        if (read <= 0 && loop) {
            // EOF → 先頭に戻ってループ
            inputStream.reset()
            pcmStart = -1L
            ensureHeaderSkipped()
            read = inputStream.read(byteBuf, 0, byteBuf.size)
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
                inputStream.mark(Int.MAX_VALUE)
                // WAV header 44 bytes
                @Suppress("KotlinConstantNowInFuture")
                inputStream.skip(44)
                pcmStart = 44
            }
            headerSkipped = true
        }
    }

    override fun stop() { isActive = false }

    override fun release() {
        isActive = false
        inputStream.close()
    }
}
