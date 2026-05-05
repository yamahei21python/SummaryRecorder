package com.kohei.summaryrecorder.audio

import android.media.AudioFormat
import android.media.AudioRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RealAudioProviderTest {

    private lateinit var provider: RealAudioProvider

    @Before
    fun setUp() {
        provider = RealAudioProvider()
        mockkStatic(AudioRecord::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `start returns false when getMinBufferSize is ERROR_BAD_VALUE`() {
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns AudioRecord.ERROR_BAD_VALUE

        val result = provider.start()
        assertFalse(result)
    }

    @Test
    fun `start returns false when getMinBufferSize is ERROR`() {
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns AudioRecord.ERROR

        val result = provider.start()
        assertFalse(result)
    }

    @Test
    fun `start returns false when AudioRecord state is not initialized`() {
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 2048

        mockkConstructor(AudioRecord::class)
        every { anyConstructed<AudioRecord>().state } returns AudioRecord.STATE_UNINITIALIZED

        val result = provider.start()
        assertFalse(result)
    }

    @Test
    fun `start returns true when initialized successfully`() {
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 2048

        mockkConstructor(AudioRecord::class)
        every { anyConstructed<AudioRecord>().state } returns AudioRecord.STATE_INITIALIZED
        every { anyConstructed<AudioRecord>().startRecording() } returns Unit

        val result = provider.start()
        assertTrue(result)
    }

    @Test
    fun `read returns -1 when audioRecord is null`() {
        val buffer = ShortArray(1024)
        val read = provider.read(buffer, 1024)
        assertEquals(-1, read)
    }

    @Test
    fun `stop double invocation does not throw exception`() {
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 2048
        mockkConstructor(AudioRecord::class)
        every { anyConstructed<AudioRecord>().state } returns AudioRecord.STATE_INITIALIZED
        every { anyConstructed<AudioRecord>().startRecording() } returns Unit
        every { anyConstructed<AudioRecord>().stop() } returns Unit

        provider.start()

        provider.stop()
        provider.stop() // Double call

        verify(exactly = 2) { anyConstructed<AudioRecord>().stop() }
    }

    @Test
    fun `release double invocation does not throw exception and sets record to null`() {
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 2048
        mockkConstructor(AudioRecord::class)
        every { anyConstructed<AudioRecord>().state } returns AudioRecord.STATE_INITIALIZED
        every { anyConstructed<AudioRecord>().startRecording() } returns Unit
        every { anyConstructed<AudioRecord>().release() } returns Unit

        provider.start()

        provider.release()
        provider.release() // Double call

        verify(exactly = 1) { anyConstructed<AudioRecord>().release() }

        // After release, read should return -1 because audioRecord is null
        val buffer = ShortArray(1024)
        val read = provider.read(buffer, 1024)
        assertEquals(-1, read)
    }
}
