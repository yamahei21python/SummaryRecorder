package com.kohei.summaryrecorder.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.audio.RealAudioProvider
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import com.kohei.summaryrecorder.audio.DebugConfig
import io.mockk.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertIs

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

    @Test
    fun `after fix - providers respect runtime debugMode changes`() {
        // 1. 最初は debugMode = false
        DebugConfig.debugMode = false
        val provider1 = module.provideTranscriptionProvider(mockRepo)
        assertIs<TranscriptionRepository>(provider1, "最初は実体リポジトリが返る")

        // 2. 途中で debugMode = true に変更
        DebugConfig.debugMode = true
        val provider2 = module.provideTranscriptionProvider(mockRepo)
        assertIs<MockTranscriptionProvider>(provider2, "debugMode変更後はMockが返る")
    }

    @Test
    fun `audio provider also respects runtime debugMode changes`() {
        val mockContext = mockk<Context>()
        val mockAssets = mockk<android.content.res.AssetManager>()
        every { mockContext.assets } returns mockAssets
        // ダミーのWAVヘッダー（44バイト）+ データ
        val dummyData = ByteArray(100)
        every { mockAssets.open("dummy_audio.wav") } returns dummyData.inputStream()

        DebugConfig.debugMode = false
        val audio1 = module.provideAudioProvider(mockContext)
        assertIs<RealAudioProvider>(audio1)

        DebugConfig.debugMode = true
        val audio2 = module.provideAudioProvider(mockContext)
        assertIs<DummyAudioProvider>(audio2)
    }
}
