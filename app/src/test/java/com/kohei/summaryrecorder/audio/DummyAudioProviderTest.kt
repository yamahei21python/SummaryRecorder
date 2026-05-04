package com.kohei.summaryrecorder.audio

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DummyAudioProvider: ループ・遅延・複数サイクル検証。
 *
 * 検証項目:
 * - EOF到達時 loop=true → 先頭から再読込
 * - loop=false → EOF到達時 -1 返却
 * - readDelayMs > 0 時に正しく遅延する
 * - 複数 start/stop サイクルでリソース解放の冪等性
 * - PCMデータがWAVヘッダ(44byte)スキップ後に正しく読める
 */
class DummyAudioProviderTest {

    /** 44byteヘッダー + 20byte PCMデータ(10 samples × 16bit) */
    private val wavData: ByteArray by lazy {
        ByteBuffer.allocate(64).apply {
            // 44byte WAVヘッダー (最小限)
            put("RIFF".toByteArray())
            putIntLE(56) // fileSize - 8 = 64 - 8
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putIntLE(16) // fmt chunk size
            putShortLE(1) // PCM
            putShortLE(1) // mono
            putIntLE(16000) // sampleRate
            putIntLE(32000) // byteRate
            putShortLE(2) // blockAlign
            putShortLE(16) // bitsPerSample
            put("data".toByteArray())
            putIntLE(20) // dataLength
            // 20 bytes PCM data (10 samples)
            for (i in 0 until 10) {
                putShortLE(i.toShort())
            }
        }.array()
    }

    private fun ByteBuffer.putIntLE(value: Int) {
        put((value and 0xFF).toByte())
        put(((value shr 8) and 0xFF).toByte())
        put(((value shr 16) and 0xFF).toByte())
        put(((value shr 24) and 0xFF).toByte())
    }

    private fun ByteBuffer.putShortLE(value: Short) {
        put((value.toInt() and 0xFF).toByte())
        put(((value.toInt() shr 8) and 0xFF).toByte())
    }

    private fun ByteBuffer.putShortLE(value: Int) {
        putShortLE(value.toShort())
    }

    private fun createProvider(
        loop: Boolean = true,
        readDelayMs: Long = 0L
    ): DummyAudioProvider {
        val stream = ByteArrayInputStream(wavData)
        return DummyAudioProvider(
            inputStream = stream,
            readDelayMs = readDelayMs,
            loop = loop
        )
    }

    // ===== EOFループ =====

    @Test
    fun `loop=true reads PCM data and loops on EOF`() {
        val provider = createProvider(loop = true)
        provider.start()

        val buffer = ShortArray(10)
        // 1回目: PCM 10 samples読込
        val read1 = provider.read(buffer, 10)
        assertEquals(10, read1)

        // PCMデータ確認 (sample 0-9)
        for (i in 0 until 10) {
            assertEquals(i.toShort(), buffer[i])
        }

        // EOF到達 → ループ → 再度10 samples読込
        val read2 = provider.read(buffer, 10)
        assertEquals(10, read2)

        // ループ後も同じデータ
        for (i in 0 until 10) {
            assertEquals(i.toShort(), buffer[i])
        }

        provider.stop()
        provider.release()
    }

    @Test
    fun `loop=false returns -1 on EOF`() {
        val provider = createProvider(loop = false)
        provider.start()

        val buffer = ShortArray(10)
        // 1回目: PCM 10 samples
        val read1 = provider.read(buffer, 10)
        assertEquals(10, read1)

        // EOF → -1
        val read2 = provider.read(buffer, 10)
        assertEquals(-1, read2)

        provider.stop()
        provider.release()
    }

    @Test
    fun `loop=true reads many cycles without error`() {
        val provider = createProvider(loop = true)
        provider.start()

        val buffer = ShortArray(5)

        // 5サンプルずつ100回読込 = 500サンプル = 50ループ分
        repeat(100) {
            val read = provider.read(buffer, 5)
            assertTrue(read > 0, "Read should succeed on iteration $it")
        }

        provider.stop()
        provider.release()
    }

    // ===== 遅延 =====

    @Test
    fun `readDelayMs causes measurable delay`() {
        val delayMs = 50L
        val provider = createProvider(loop = true, readDelayMs = delayMs)
        provider.start()

        val buffer = ShortArray(5)

        val start = System.currentTimeMillis()
        provider.read(buffer, 5)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(
            elapsed >= delayMs - 10, // 許容誤差10ms
            "Expected >= ${delayMs - 10}ms delay, got ${elapsed}ms"
        )

        provider.stop()
        provider.release()
    }

    @Test
    fun `readDelayMs=0 has no delay`() {
        val provider = createProvider(loop = true, readDelayMs = 0L)
        provider.start()

        val buffer = ShortArray(5)

        val start = System.currentTimeMillis()
        repeat(20) { provider.read(buffer, 5) }
        val elapsed = System.currentTimeMillis() - start

        // 20回読込が100ms以内に完了（遅延なし）
        assertTrue(
            elapsed < 100,
            "Expected fast reads, took ${elapsed}ms"
        )

        provider.stop()
        provider.release()
    }

    // ===== 複数start/stopサイクル =====

    @Test
    fun `multiple start-stop cycles work correctly`() {
        val provider = createProvider(loop = true)

        repeat(3) { cycle ->
            provider.start()
            val buffer = ShortArray(5)
            val read = provider.read(buffer, 5)
            assertTrue(read > 0, "Cycle $cycle: read should succeed")
            provider.stop()
        }

        provider.release()
    }

    @Test
    fun `release after multiple cycles is safe`() {
        val provider = createProvider(loop = true)

        repeat(3) {
            provider.start()
            val buffer = ShortArray(5)
            provider.read(buffer, 5)
            provider.stop()
        }

        // release()複数回呼び出し安全
        provider.release()
        // 2回目release → inputStream.close()の2回目
        // ByteArrayInputStream.close()は何もしないので安全
        provider.release()
    }

    // ===== WAVヘッダースキップ =====

    @Test
    fun `first read skips 44-byte WAV header`() {
        val provider = createProvider(loop = true)
        provider.start()

        val buffer = ShortArray(10)
        val read = provider.read(buffer, 10)

        assertEquals(10, read)
        // PCMデータ: 0,1,2,...,9 (WAVヘッダー後のデータ)
        assertEquals(0.toShort(), buffer[0])
        assertEquals(9.toShort(), buffer[9])

        provider.stop()
        provider.release()
    }

    @Test
    fun `read after stop returns -1`() {
        val provider = createProvider(loop = true)
        provider.start()

        val buffer = ShortArray(10)
        provider.read(buffer, 10)

        provider.stop()

        // stop後 → isActive=false → -1
        val read = provider.read(buffer, 10)
        assertEquals(-1, read)

        provider.release()
    }
}
