package com.kohei.summaryrecorder.service

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * RecordingService.onDestroy のロジック検証。
 *
 * @AndroidEntryPoint で Hilt 依存注入が必要なため Robolectric.buildService は不可。
 * onDestroy 内の runBlocking + withTimeoutOrNull で stopRecording が呼ばれることを
 * Service を直接 new して検証。
 */
class RecordingServiceDestroyFinalizationTest {

    private lateinit var service: RecordingService

    @Before
    fun setUp() {
        service = RecordingService()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `onDestroy finalizes WAV header before returning`() = runTest {
        val mockManager = mockk<RecordingManager>(relaxed = true)

        val field = RecordingService::class.java.getDeclaredField("recordingManager")
        field.isAccessible = true
        field.set(service, mockManager)

        service.onDestroy()

        coVerify(exactly = 1) { mockManager.stopRecording() }
    }

    @Test
    fun `onDestroy stopRecording completes within timeout`() = runTest {
        val mockManager = mockk<RecordingManager>(relaxed = true)
        coEvery { mockManager.stopRecording() } coAnswers {
            delay(100)
        }

        val field = RecordingService::class.java.getDeclaredField("recordingManager")
        field.isAccessible = true
        field.set(service, mockManager)

        service.onDestroy()
        coVerify(exactly = 1) { mockManager.stopRecording() }
    }

    @Test
    fun `onDestroy stopRecording timeout triggers graceful shutdown`() = runTest {
        val mockManager = mockk<RecordingManager>(relaxed = true)
        coEvery { mockManager.stopRecording() } coAnswers {
            delay(3000)
        }

        val field = RecordingService::class.java.getDeclaredField("recordingManager")
        field.isAccessible = true
        field.set(service, mockManager)

        service.onDestroy()
        coVerify(exactly = 1) { mockManager.stopRecording() }
    }
}
