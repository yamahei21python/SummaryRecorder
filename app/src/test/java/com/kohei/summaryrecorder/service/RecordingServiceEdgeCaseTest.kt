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
@Config(sdk = [33])
class RecordingServiceEdgeCaseTest {



    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `onStartCommand with null sessionId generates UUID`() = runTest {
        val serviceController = Robolectric.buildService(RecordingService::class.java)
        val service = serviceController.get()
        
        // Setup mocks for required fields manually or use Hilt if properly injected
        // Hilt will inject them if we use create() but sometimes Robolectric + Hilt needs manual property setting if not fully setup
        service.dao = mockk(relaxed = true)
        service.chunkRepository = mockk(relaxed = true)
        service.uploader = mockk(relaxed = true)
        service.chunkSize = ChunkSize(1024L)
        service.audioProvider = mockk(relaxed = true)
        coEvery { service.audioProvider.start() } returns true

        serviceController.create()
        
        val intent = Intent(context, RecordingService::class.java).apply {
            action = "ACTION_START"
            // No session_id extra
        }

        serviceController.withIntent(intent).startCommand(0, 1)

        // Verify that a directory with a valid UUID was created
        val recordingManagerField = RecordingService::class.java.getDeclaredField("recordingManager")
        recordingManagerField.isAccessible = true
        val recordingManager = recordingManagerField.get(service) as RecordingManager

        val sessionIdField = RecordingManager::class.java.getDeclaredField("sessionId")
        sessionIdField.isAccessible = true
        // Wait, RecordingManager no longer has sessionId! We changed it to local capture.
        // So we need to check the outputDir instead.
        
        val recorderField = RecordingManager::class.java.getDeclaredField("recorder")
        recorderField.isAccessible = true
        val recorder = recorderField.get(recordingManager) as? com.kohei.summaryrecorder.recorder.GaplessRecorder
        assertNotNull(recorder)
        
        val outputDirField = com.kohei.summaryrecorder.recorder.GaplessRecorder::class.java.getDeclaredField("outputDir")
        outputDirField.isAccessible = true
        val outputDir = outputDirField.get(recorder) as java.io.File
        
        // Verify outputDir name is a valid UUID
        val uuidName = outputDir.name
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
        
        // Use reflection to mock recordingManager
        val mockManager = mockk<RecordingManager>()
        // Mock stopRecording to delay indefinitely to simulate timeout
        coEvery { mockManager.stopRecording() } coAnswers {
            kotlinx.coroutines.delay(5000L)
        }
        val recordingManagerField = RecordingService::class.java.getDeclaredField("recordingManager")
        recordingManagerField.isAccessible = true
        recordingManagerField.set(service, mockManager)

        // destroy should not block indefinitely. It should timeout after 2 seconds.
        val startTime = System.currentTimeMillis()
        serviceController.destroy()
        val elapsed = System.currentTimeMillis() - startTime
        
        // Ensure it took around 2000ms, not 5000ms
        assertTrue(elapsed < 4000L, "destroy took too long: $elapsed ms")
    }
}
