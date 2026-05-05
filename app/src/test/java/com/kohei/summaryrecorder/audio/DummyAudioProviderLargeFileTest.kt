package com.kohei.summaryrecorder.audio

import com.kohei.summaryrecorder.domain.repository.AudioProvider
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

class DummyAudioProviderLargeFileTest {

    @Test
    fun `loop with large audio data works correctly`() {
        // 2MBのダミーデータ (mark/resetの1MB制限を超えるサイズ)
        val largeData = ByteArray(2 * 1024 * 1024) { it.toByte() }
        val provider = DummyAudioProvider(ByteArrayInputStream(largeData))

        provider.start()

        val buffer = ShortArray(1024)
        // 最後まで読み進める
        val iterations = (largeData.size / (buffer.size * 2))
        for (i in 0 until iterations) {
            provider.read(buffer, buffer.size)
        }

        // 次の読み込みでループが発生するはず
        val read = provider.read(buffer, buffer.size)
        assertEquals(buffer.size, read, "ループ後も正しく読み込めること")
    }
}
