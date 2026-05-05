package com.kohei.summaryrecorder.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * RecordingService: Intent生成ヘルパー検証。
 *
 * 検証項目:
 * - startIntent(): action=ACTION_START, extra=session_id
 * - stopIntent(): action=ACTION_STOP
 * - startIntent()のtarget componentがRecordingService
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class RecordingServiceIntentTest {

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
    fun `startIntent has ACTION_START and sessionId extra`() {
        val intent = RecordingService.startIntent(context, "test-session-123")

        assertEquals("ACTION_START", intent.action)
        assertEquals("test-session-123", intent.getStringExtra("session_id"))
    }

    @Test
    fun `startIntent targets RecordingService component`() {
        val intent = RecordingService.startIntent(context, "sess")

        val component = intent.component
        assertEquals(
            "com.kohei.summaryrecorder.service.RecordingService",
            component?.className
        )
    }

    @Test
    fun `stopIntent has ACTION_STOP`() {
        val intent = RecordingService.stopIntent(context)

        assertEquals("ACTION_STOP", intent.action)
    }

    @Test
    fun `stopIntent targets RecordingService component`() {
        val intent = RecordingService.stopIntent(context)

        val component = intent.component
        assertEquals(
            "com.kohei.summaryrecorder.service.RecordingService",
            component?.className
        )
    }

    @Test
    fun `startIntent with empty sessionId stores empty string`() {
        val intent = RecordingService.startIntent(context, "")

        assertEquals("", intent.getStringExtra("session_id"))
    }
}
