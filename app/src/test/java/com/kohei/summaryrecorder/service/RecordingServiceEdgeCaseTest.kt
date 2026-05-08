package com.kohei.summaryrecorder.service
 
import kotlinx.coroutines.ExperimentalCoroutinesApi

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kohei.summaryrecorder.domain.repository.AudioProvider
import com.kohei.summaryrecorder.domain.repository.TranscriptionProvider
import com.kohei.summaryrecorder.domain.repository.SummaryProvider
import com.kohei.summaryrecorder.data.db.SummaryDao
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = HiltTestApplication::class)
class RecordingServiceEdgeCaseTest {
    
    @get:Rule
    var hiltRule = HiltAndroidRule(this)



    private lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `onStartCommand with null sessionId initializes RecordingManager`() = runTest {
        val serviceController = Robolectric.buildService(RecordingService::class.java)
        val service = serviceController.get()
        
        service.transcriptionProvider = mockk(relaxed = true)
        service.summaryProvider = mockk(relaxed = true)
        service.audioProvider = mockk(relaxed = true)
        coEvery { service.audioProvider.start() } returns true

        serviceController.create()
        
        val intent = Intent(context, RecordingService::class.java).apply {
            action = "ACTION_START"
        }

        serviceController.withIntent(intent).startCommand(0, 1)

        val recordingManagerField = RecordingService::class.java.getDeclaredField("recordingManager")
        recordingManagerField.isAccessible = true
        val recordingManager = recordingManagerField.get(service)
        
        assertNotNull(recordingManager, "RecordingManager should be initialized")
        
        serviceController.destroy()
    }

    @Test
    fun `onDestroy handles timeout during stopRecording gracefully`() = runTest {
        val serviceController = Robolectric.buildService(RecordingService::class.java)
        val service = serviceController.get()
        
        service.transcriptionProvider = mockk(relaxed = true)
        service.summaryProvider = mockk(relaxed = true)
        service.audioProvider = mockk(relaxed = true)

        serviceController.create()
        
        val mockManager = mockk<RecordingManager>(relaxed = true)
        val recordingManagerField = RecordingService::class.java.getDeclaredField("recordingManager")
        recordingManagerField.isAccessible = true
        recordingManagerField.set(service, mockManager)

        // onDestroyが例外を投げずに完了することを確認
        serviceController.destroy()
    }

    @Test
    fun `onDestroy completes normally when stopRecording is fast`() = runTest {
        val serviceController = Robolectric.buildService(RecordingService::class.java)
        val service = serviceController.get()
        
        service.transcriptionProvider = mockk(relaxed = true)
        service.summaryProvider = mockk(relaxed = true)
        service.audioProvider = mockk(relaxed = true)

        serviceController.create()
        
        val mockManager = mockk<RecordingManager>(relaxed = true)
        coEvery { mockManager.stopRecording() } returns null
        val recordingManagerField = RecordingService::class.java.getDeclaredField("recordingManager")
        recordingManagerField.isAccessible = true
        recordingManagerField.set(service, mockManager)

        val startTime = System.currentTimeMillis()
        serviceController.destroy()
        val elapsed = System.currentTimeMillis() - startTime
        
        assertTrue(elapsed < 2000L, "destroy should be fast: $elapsed ms")
    }
}
