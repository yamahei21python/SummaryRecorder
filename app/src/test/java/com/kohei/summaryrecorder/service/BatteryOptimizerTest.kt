package com.kohei.summaryrecorder.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class BatteryOptimizerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `checkAndNotify creates notification channel and shows notification`() {
        BatteryOptimizer.checkAndNotify(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)

        // Verify channel creation
        val channel = shadowNotificationManager.getNotificationChannel("battery_opt")
        assertNotNull(channel, "Battery optimization channel should be created")

        // Verify notification was shown
        val notifications = shadowNotificationManager.allNotifications
        assertNotNull(notifications.find { it.channelId == "battery_opt" }, "Battery optimization notification should be shown")
    }
}
