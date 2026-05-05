package com.kohei.summaryrecorder.di

import android.content.Context
import android.content.res.AssetManager
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.data.model.SummaryResult
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
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

    @Test
    fun `provideAudioProvider debugMode starts with dummy data`() {
        DebugConfig.debugMode = true
        val mockContext = mockk<Context>()
        val mockAssets = mockk<AssetManager>()
        every { mockContext.assets } returns mockAssets
        every { mockAssets.open("dummy_audio.wav") } throws FileNotFoundException("not found")

        val provider = ConfigModule.provideAudioProvider(mockContext)
        assertTrue(provider.start(), "debugMode時はDummyAudioProvider経由でstart成功")
        provider.release()
    }

    @Test
    fun `provideAudioProvider productionMode returns nonNull provider`() {
        DebugConfig.debugMode = false
        val mockContext = mockk<Context>()

        val provider = ConfigModule.provideAudioProvider(mockContext)
        // Lazy wrapper; 本番モードではRealAudioProviderに委譲
        provider.release()
    }

    @Test
    fun `provideTranscriptionProvider returns mock result when debugMode`() = runBlocking {
        DebugConfig.debugMode = true
        val provider = ConfigModule.provideTranscriptionProvider(mockk())
        val result = provider.transcribe(File("dummy.wav"))
        assertTrue(result.isSuccess)
        assertEquals("これはテスト用の文字起こし結果です。", result.getOrThrow())
    }

    @Test
    fun `provideTranscriptionProvider delegates to real when not debugMode`() = runBlocking {
        DebugConfig.debugMode = false
        val mockRepo = mockk<TranscriptionRepository> {
            coEvery { transcribe(any()) } returns Result.success("real result")
        }
        val provider = ConfigModule.provideTranscriptionProvider(mockRepo)
        val result = provider.transcribe(File("test.wav"))
        assertTrue(result.isSuccess)
        assertEquals("real result", result.getOrThrow())
    }

    @Test
    fun `provideSummaryProvider returns mock result when debugMode`() = runBlocking {
        DebugConfig.debugMode = true
        val provider = ConfigModule.provideSummaryProvider(mockk())
        val result = provider.summarize("test input")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().summaryText.contains("E2Eテスト要約"))
    }

    @Test
    fun `provideSummaryProvider delegates to real when not debugMode`() = runBlocking {
        DebugConfig.debugMode = false
        val mockRepo = mockk<SummaryRepository> {
            coEvery { summarize(any()) } returns Result.success(SummaryResult("タイトル", "real summary"))
        }
        val provider = ConfigModule.provideSummaryProvider(mockRepo)
        val result = provider.summarize("test input")
        assertTrue(result.isSuccess)
        assertEquals("real summary", result.getOrThrow().summaryText)
    }
}
