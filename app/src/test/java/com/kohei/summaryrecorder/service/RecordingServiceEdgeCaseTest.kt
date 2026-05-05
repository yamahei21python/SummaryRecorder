package com.kohei.summaryrecorder.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.data.db.ChunkDao
import com.kohei.summaryrecorder.di.ChunkSize
import com.kohei.summaryrecorder.domain.provider.AudioProvider
import com.kohei.summaryrecorder.domain.provider.ChunkRepository
import com.kohei.summaryrecorder.domain.usecase.TranscriptionUploader
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.UUID
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class RecordingServiceEdgeCaseTest {



    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `onStartCommand with null sessionId generates UUID`() = runTest {
        val serviceController = Robolectric.buildService(RecordingService::class.java)
        val service = serviceController.get()
        
        service.dao = mockk(relaxed = true)
        service.chunkRepository = mockk(relaxed = true)
        service.uploader = mockk(relaxed = true)
        service.chunkSize = ChunkSize(1024L)
        service.audioProvider = mockk(relaxed = true)
        coEvery { service.audioProvider.start() } returns true

        serviceController.create()
        
        val intent = Intent(context, RecordingService::class.java).apply {
            action = "ACTION_START"
        }

        serviceController.withIntent(intent).startCommand(0, 1)

        val recordingManagerField = RecordingService::class.java.getDeclaredField("recordingManager")
        recordingManagerField.isAccessible = true
        val recordingManager = recordingManagerField.get(service) as RecordingManager

        // Since we can't easily get the sessionId from the new RecordingManager,
        // we'll check if any directory was created in filesDir/recordings/
        val recordingsDir = java.io.File(context.filesDir, "recordings")
        val sessionDirs = recordingsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        
        assertTrue(sessionDirs.isNotEmpty(), "A session directory should be created")
        val uuidName = sessionDirs.first().name
        val parsedUuid = UUID.fromString(uuidName)
        assertNotNull(parsedUuid)
        
        serviceController.destroy()
    }

    @Test
    fun `onDestroy handles timeout during stopRecording gracefully`() = runTest {
        val serviceController = Robolectric.buildService(RecordingService::class.java)
        val service = serviceController.get()
        
        service.dao = mockk(relaxed = true)
        service.chunkRepository = mockk(relaxed = true)
        service.uploader = mockk(relaxed = true)
        service.chunkSize = ChunkSize(1024L)
        service.audioProvider = mockk(relaxed = true)

        serviceController.create()
        
        val mockManager = mockk<RecordingManager>(relaxed = true)
        // Simulate a long-running stopRecording
        coEvery { mockManager.stopRecording() } coAnswers {
            kotlinx.coroutines.delay(10000L)
        }
        val recordingManagerField = RecordingService::class.java.getDeclaredField("recordingManager")
        recordingManagerField.isAccessible = true
        recordingManagerField.set(service, mockManager)

        // onDestroy uses runBlocking + withTimeoutOrNull(2000L).
        // In Robolectric/runTest, we need to be careful with real vs virtual time.
        // We just want to ensure it doesn't crash and returns within a reasonable real time.
        val startTime = System.currentTimeMillis()
        serviceController.destroy()
        val elapsed = System.currentTimeMillis() - startTime
        
        // Timeout is 2000ms. So it should take at least ~2000ms real time if it waits,
        // OR it might return immediately if virtual time advances.
        // The important thing is it DOES return and doesn't wait 10000ms.
        assertTrue(elapsed < 5000L, "destroy took too long: $elapsed ms")
    }
}
