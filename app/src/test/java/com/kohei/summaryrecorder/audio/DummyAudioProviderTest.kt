package com.kohei.summaryrecorder.audio

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DummyAudioProvider: ループ・遅延・複数サイクル検証。
 */
class DummyAudioProviderTest {

    /** 44byteヘッダー + 20byte PCMデータ(10 samples × 16bit) */
    private val wavData: ByteArray by lazy {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // RIFF header
        baos.write("RIFF".toByteArray())
        writeLittleEndianInt(dos, 56) // fileSize - 8
        baos.write("WAVE".toByteArray())

        // fmt sub-chunk
        baos.write("fmt ".toByteArray())
        writeLittleEndianInt(dos, 16) // fmt chunk size
        writeLittleEndianShort(dos, 1) // PCM
        writeLittleEndianShort(dos, 1) // mono
        writeLittleEndianInt(dos, 16000) // sampleRate
        writeLittleEndianInt(dos, 32000) // byteRate
        writeLittleEndianShort(dos, 2) // blockAlign
        writeLittleEndianShort(dos, 16) // bitsPerSample

        // data sub-chunk
        baos.write("data".toByteArray())
        writeLittleEndianInt(dos, 20) // dataLength = 10 samples × 2 bytes

        // PCM data (10 samples)
        for (i in 0 until 10) {
            writeLittleEndianShort(dos, i.toShort())
        }

        baos.toByteArray()
    }

    private fun writeLittleEndianInt(dos: DataOutputStream, value: Int) {
        dos.writeByte(value and 0xFF)
        dos.writeByte((value shr 8) and 0xFF)
        dos.writeByte((value shr 16) and 0xFF)
        dos.writeByte((value shr 24) and 0xFF)
    }

    private fun writeLittleEndianShort(dos: DataOutputStream, value: Short) {
        dos.writeByte(value.toInt() and 0xFF)
        dos.writeByte((value.toInt() shr 8) and 0xFF)
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

        // 2周目のデータもPCM（0-9）であること。WAVヘッダ（44バイト）が混入していないことを検証
        for (i in 0 until 10) {
            assertEquals(i.toShort(), buffer[i], "2周目のデータが不正です。WAVヘッダがスキップされていない可能性があります。index=$i")
        }

        provider.stop()
        provider.release()
    }

    @Test
    fun `loop=false returns -1 on EOF`() {
        val provider = createProvider(loop = false)
        provider.start()

        val buffer = ShortArray(10)
        val read1 = provider.read(buffer, 10)
        assertEquals(10, read1)

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
            elapsed >= delayMs - 10,
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

        assertTrue(elapsed < 100, "Expected fast reads, took ${elapsed}ms")

        provider.stop()
        provider.release()
    }

    // ===== 複数start/stopサイクル =====

    @Test
    fun `multiple start-stop cycles work correctly`() {
        // 各サイクルで新しいproviderを作成（InputStream positionリセット）
        repeat(3) { cycle ->
            val provider = createProvider(loop = true)
            provider.start()
            val buffer = ShortArray(5)
            val read = provider.read(buffer, 5)
            assertTrue(read > 0, "Cycle $cycle: read should succeed, got $read")
            provider.stop()
            provider.release()
        }
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

        provider.release()
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

        // PCMデータ: 0,1,2,...,9
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

        val read = provider.read(buffer, 10)
        assertEquals(-1, read)

        provider.release()
    }
}
