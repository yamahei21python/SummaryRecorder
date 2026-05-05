package com.kohei.summaryrecorder.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordingServiceDestroyFinalizationTest {

    private lateinit var controller: ServiceController<RecordingService>
    private lateinit var service: RecordingService
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .filesDir.resolve("test_finalization").also { it.mkdirs() }

        // create() + attach() でonCreateが呼ばれ、Hilt依存が注入される
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
