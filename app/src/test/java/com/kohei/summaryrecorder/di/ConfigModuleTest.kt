package com.kohei.summaryrecorder.di

import android.content.Context
import android.content.res.AssetManager
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.RealAudioProvider
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigModuleTest {

    @BeforeEach
    fun setUp() {
        DebugConfig.debugMode = false
    }

    @AfterEach
    fun tearDown() {
        DebugConfig.debugMode = false
        unmockkAll()
    }

    @Test
    fun `ChunkSize returns debug bytes when debugMode`() {
        DebugConfig.debugMode = true
        val chunkSize = ConfigModule.provideChunkSize()
        assertEquals(DebugConfig.DEBUG_CHUNK_BYTES, chunkSize.bytes)
    }

    @Test
    fun `ChunkSize returns production bytes when not debugMode`() {
        DebugConfig.debugMode = false
        val chunkSize = ConfigModule.provideChunkSize()
        assertEquals(DebugConfig.PRODUCTION_CHUNK_BYTES, chunkSize.bytes)
    }

    // B7: assets ファイル欠落時テスト
    @Test
    fun `provideAudioProvider debugMode missing asset returns DummyWithEmptyData`() {
        DebugConfig.debugMode = true
        val mockContext = mockk<Context>()
        val mockAssets = mockk<AssetManager>()
        every { mockContext.assets } returns mockAssets
        every { mockAssets.open("dummy_audio.wav") } throws FileNotFoundException("not found")

        val provider = ConfigModule.provideAudioProvider(mockContext)

        assertTrue(provider is DummyAudioProvider, "FileNotFoundException時はDummyAudioProviderが返ること")
    }

    @Test
    fun `provideAudioProvider productionMode returns RealAudioProvider`() {
        DebugConfig.debugMode = false
        val mockContext = mockk<Context>()

        val provider = ConfigModule.provideAudioProvider(mockContext)

        assertTrue(provider is RealAudioProvider, "本番モードではRealAudioProviderが返ること")
    }

    @Test
    fun `provideTranscriptionProvider returns mock when debugMode`() {
        DebugConfig.debugMode = true
        val provider = ConfigModule.provideTranscriptionProvider(mockk())
        assertTrue(provider is MockTranscriptionProvider)
    }

    @Test
    fun `provideTranscriptionProvider returns real when not debugMode`() {
        DebugConfig.debugMode = false
        val provider = ConfigModule.provideTranscriptionProvider(mockk())
        assertFalse(provider is MockTranscriptionProvider)
    }

    @Test
    fun `provideSummaryProvider returns mock when debugMode`() {
        DebugConfig.debugMode = true
        val provider = ConfigModule.provideSummaryProvider(mockk())
        assertTrue(provider is MockSummaryProvider)
    }

    @Test
    fun `provideSummaryProvider returns real when not debugMode`() {
        DebugConfig.debugMode = false
        val provider = ConfigModule.provideSummaryProvider(mockk())
        assertFalse(provider is MockSummaryProvider)
    }
}
