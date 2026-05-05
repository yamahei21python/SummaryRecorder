package com.kohei.summaryrecorder.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.audio.DebugConfig
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ConfigModuleSingletonTimingTest {

    private lateinit var context: Context
    private lateinit var module: ConfigModule
    private lateinit var mockRepo: TranscriptionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        module = ConfigModule
        mockRepo = mockk()
    }

    @After
    fun tearDown() {
        DebugConfig.debugMode = false
    }

    @Test
    fun `after fix - providers respect runtime debugMode changes`() = runBlocking {
        // 1. debugMode = false → real repoに委譲
        DebugConfig.debugMode = false
        coEvery { mockRepo.transcribe(any()) } returns Result.success("real")
        val provider1 = module.provideTranscriptionProvider(mockRepo)
        val result1 = provider1.transcribe(File("test.wav"))
        assertTrue(result1.isSuccess)
        assertEquals("real", result1.getOrThrow())

        // 2. debugMode = true → mockに委譲
        DebugConfig.debugMode = true
        val provider2 = module.provideTranscriptionProvider(mockRepo)
        val result2 = provider2.transcribe(File("test.wav"))
        assertTrue(result2.isSuccess)
        assertEquals("これはテスト用の文字起こし結果です。", result2.getOrThrow())
    }

    @Test
    fun `audio provider also respects runtime debugMode changes`() {
        val mockContext = mockk<Context>()
        val mockAssets = mockk<android.content.res.AssetManager>()
        every { mockContext.assets } returns mockAssets
        val dummyData = ByteArray(100)
        every { mockAssets.open("dummy_audio.wav") } returns dummyData.inputStream()

        // 本番モード → Lazy wrapper返却（AudioRecord不可だがprovider自体は生成される）
        DebugConfig.debugMode = false
        val audio1 = module.provideAudioProvider(mockContext)
        audio1.release()

        // debugモード → DummyAudioProvider.start成功
        DebugConfig.debugMode = true
        val audio2 = module.provideAudioProvider(mockContext)
        assertTrue(audio2.start(), "debugMode時はDummyAudioProvider経由でstart成功")
        audio2.release()
    }
}
