package com.kohei.summaryrecorder.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = HiltTestApplication::class)
class RecordingServiceDestroyFinalizationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var controller: ServiceController<RecordingService>
    private lateinit var service: RecordingService
    private lateinit var tempDir: java.io.File

    @Before
    fun setUp() {
        hiltRule.inject()
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_finalization").also { it.mkdirs() }

        controller = Robolectric.buildService(RecordingService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun `onDestroy finalizes WAV header before returning`() = runTest {
        val mockManager = mockk<RecordingManager>(relaxed = true)

        val field = RecordingService::class.java.getDeclaredField("recordingManager")
        field.isAccessible = true
        field.set(service, mockManager)

        controller.destroy()

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

        controller.destroy()
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

        controller.destroy()
        coVerify(exactly = 1) { mockManager.stopRecording() }
    }
}
