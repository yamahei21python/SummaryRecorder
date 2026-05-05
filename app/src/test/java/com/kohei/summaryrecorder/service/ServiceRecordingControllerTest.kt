package com.kohei.summaryrecorder.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowApplication
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class ServiceRecordingControllerTest {

    private lateinit var context: Context
    private lateinit var controller: ServiceRecordingController
    private lateinit var shadowApp: ShadowApplication

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = ServiceRecordingController(context)
        shadowApp = shadowOf(context as android.app.Application)
    }

    @Test
    fun `startRecording starts RecordingService with correct intent`() {
        val sessionId = "test_session_123"
        controller.startRecording(sessionId)

        val intent = shadowApp.nextStartedService
        assertNotNull(intent)
        assertEquals(RecordingService::class.java.name, intent.component?.className)
        assertEquals("ACTION_START", intent.action)
        assertEquals(sessionId, intent.getStringExtra("session_id"))
    }

    @Test
    fun `stopRecording sends ACTION_STOP intent to RecordingService`() {
        controller.stopRecording()

        // #5: stopRecording now sends ACTION_STOP intent instead of stopService()
        val intent = shadowApp.nextStartedService
        assertNotNull(intent)
        assertEquals(RecordingService::class.java.name, intent.component?.className)
        assertEquals("ACTION_STOP", intent.action)
    }
}
