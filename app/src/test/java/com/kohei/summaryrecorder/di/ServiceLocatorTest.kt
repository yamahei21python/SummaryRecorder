package com.kohei.summaryrecorder.di

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.audio.DebugConfig
import com.kohei.summaryrecorder.audio.DummyAudioProvider
import com.kohei.summaryrecorder.audio.MockSummaryProvider
import com.kohei.summaryrecorder.audio.MockTranscriptionProvider
import com.kohei.summaryrecorder.audio.RealAudioProvider
import com.kohei.summaryrecorder.data.db.AppDatabase
import com.kohei.summaryrecorder.data.repository.SummaryRepository
import com.kohei.summaryrecorder.data.repository.TranscriptionRepository
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ServiceLocator: provider選択ロジック検証。
 * Phase1(Hilt移行)で削除されるため、現状の振る舞いを固定する。
 *
 * 検証項目:
 * - debugMode=true → MockTranscriptionProvider / MockSummaryProvider
 * - debugMode=false → TranscriptionRepository / SummaryRepository
 * - mockTranscriptionProvider設定時 → そちらが優先
 * - clearTestOverrides() → 全override解除
 * - overrideForTest → database/repository差し替え
 * - createAudioProvider → debugModeでDummyAudioProvider
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], application = Application::class)
class ServiceLocatorTest {

    @Before
    fun setUp() {
        DebugConfig.debugMode = false
        ServiceLocator.clearTestOverrides()
        ServiceLocator.mockTranscriptionProvider = null
        ServiceLocator.mockSummaryProvider = null
    }

    @After
    fun tearDown() {
        DebugConfig.debugMode = false
        ServiceLocator.clearTestOverrides()
        ServiceLocator.mockTranscriptionProvider = null
        ServiceLocator.mockSummaryProvider = null
        unmockkAll()
    }

    // ===== transcriptionProvider =====

    @Test
    fun `debugMode=true returns MockTranscriptionProvider`() {
        DebugConfig.debugMode = true
        val provider = ServiceLocator.transcriptionProvider
        assertTrue(
            "Expected MockTranscriptionProvider, got ${provider::class.simpleName}",
            provider is MockTranscriptionProvider
        )
    }

    @Test
    fun `debugMode=false returns TranscriptionRepository`() {
        // transcriptionRepository は lateinit/latezy だが、
        // debugMode=falseでtranscriptionProviderにアクセスすると
        // transcriptionRepository (TranscriptionRepository) を返す。
        // ※ setApiKeys未設定だとlateinit crashするので、
        //    testTranscriptionRepoをoverrideして安全に検証
        val mockRepo = mockk<TranscriptionRepository>()
        ServiceLocator.testTranscriptionRepo = mockRepo

        val provider = ServiceLocator.transcriptionProvider
        assertSame(mockRepo, provider)
    }

    @Test
    fun `mockTranscriptionProvider takes priority over debugMode`() {
        DebugConfig.debugMode = true
        val mock = MockTranscriptionProvider()
        ServiceLocator.mockTranscriptionProvider = mock

        val provider = ServiceLocator.transcriptionProvider
        assertSame(mock, provider)
    }

    @Test
    fun `mockTranscriptionProvider takes priority when debugMode=false`() {
        DebugConfig.debugMode = false
        val mock = MockTranscriptionProvider()
        ServiceLocator.mockTranscriptionProvider = mock

        val provider = ServiceLocator.transcriptionProvider
        assertSame(mock, provider)
    }

    // ===== summaryProvider =====

    @Test
    fun `debugMode=true returns MockSummaryProvider`() {
        DebugConfig.debugMode = true
        val provider = ServiceLocator.summaryProvider
        assertTrue(
            "Expected MockSummaryProvider, got ${provider::class.simpleName}",
            provider is MockSummaryProvider
        )
    }

    @Test
    fun `debugMode=false returns SummaryRepository`() {
        val mockRepo = mockk<SummaryRepository>()
        ServiceLocator.testSummaryRepo = mockRepo

        val provider = ServiceLocator.summaryProvider
        assertSame(mockRepo, provider)
    }

    @Test
    fun `mockSummaryProvider takes priority over debugMode`() {
        DebugConfig.debugMode = true
        val mock = MockSummaryProvider()
        ServiceLocator.mockSummaryProvider = mock

        val provider = ServiceLocator.summaryProvider
        assertSame(mock, provider)
    }

    // ===== clearTestOverrides =====

    @Test
    fun `clearTestOverrides resets all test overrides`() {
        val mockDb = mockk<AppDatabase>()
        val mockTransRepo = mockk<TranscriptionRepository>()
        val mockSummaryRepo = mockk<SummaryRepository>()

        ServiceLocator.overrideForTest(mockDb, mockTransRepo)
        ServiceLocator.overrideSummaryRepository(mockSummaryRepo)

        assertNotNull(ServiceLocator.testDatabase)
        assertNotNull(ServiceLocator.testTranscriptionRepo)
        assertNotNull(ServiceLocator.testSummaryRepo)

        ServiceLocator.clearTestOverrides()

        assertEquals(null, ServiceLocator.testDatabase)
        assertEquals(null, ServiceLocator.testTranscriptionRepo)
        assertEquals(null, ServiceLocator.testSummaryRepo)
    }

    // ===== overrideForTest =====

    @Test
    fun `overrideForTest replaces database`() {
        val mockDb = mockk<AppDatabase>()
        val mockRepo = mockk<TranscriptionRepository>()

        ServiceLocator.overrideForTest(mockDb, mockRepo)

        assertSame(mockDb, ServiceLocator.database)
    }

    @Test
    fun `overrideForTest replaces transcriptionRepository`() {
        val mockDb = mockk<AppDatabase>()
        val mockRepo = mockk<TranscriptionRepository>()

        ServiceLocator.overrideForTest(mockDb, mockRepo)

        assertSame(mockRepo, ServiceLocator.transcriptionRepository)
    }

    @Test
    fun `overrideSummaryRepository replaces summaryRepo`() {
        val mockRepo = mockk<SummaryRepository>()
        ServiceLocator.overrideSummaryRepository(mockRepo)

        assertSame(mockRepo, ServiceLocator.summaryRepository)
    }

    // ===== createAudioProvider =====

    @Test
    fun `createAudioProvider debugMode=true returns DummyAudioProvider`() {
        DebugConfig.debugMode = true
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val provider = ServiceLocator.createAudioProvider(context)
        assertTrue(
            "Expected DummyAudioProvider, got ${provider::class.simpleName}",
            provider is DummyAudioProvider
        )
    }

    @Test
    fun `createAudioProvider debugMode=false returns RealAudioProvider`() {
        DebugConfig.debugMode = false
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val provider = ServiceLocator.createAudioProvider(context)
        assertTrue(
            "Expected RealAudioProvider, got ${provider::class.simpleName}",
            provider is RealAudioProvider
        )
    }

    // ===== DebugConfig =====

    @Test
    fun `DebugConfig chunkSizeBytes matches debugMode`() {
        DebugConfig.debugMode = false
        assertEquals(DebugConfig.PRODUCTION_CHUNK_BYTES, DebugConfig.chunkSizeBytes)

        DebugConfig.debugMode = true
        assertEquals(DebugConfig.DEBUG_CHUNK_BYTES, DebugConfig.chunkSizeBytes)
    }
}
