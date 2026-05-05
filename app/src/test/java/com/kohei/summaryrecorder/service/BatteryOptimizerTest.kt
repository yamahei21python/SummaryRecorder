package com.kohei.summaryrecorder.service

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BatteryOptimizer: バッテリー最適化チェック検証。
 *
 * 検証項目:
 * - shouldRequestBatteryOptimization: 最適化無視中 → false
 * - shouldRequestBatteryOptimization: 最適化有効中 → true
 * - checkAndNotify: 各ケースでクラッシュしない
 *
 * mockkでPowerManagerを制御（Robolectric Shadowに依存しない）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class BatteryOptimizerTest {

    private lateinit var context: Context
    private lateinit var mockPowerManager: PowerManager

    @Before
    fun setUp() {
        val realContext = ApplicationProvider.getApplicationContext<Context>()
        mockPowerManager = mockk<PowerManager>()
        context = mockk(relaxed = true) {
            every { packageName } returns realContext.packageName
            every { getSystemService(Context.POWER_SERVICE) } returns mockPowerManager
            every { getSystemService(Context.NOTIFICATION_SERVICE) } returns null
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `shouldRequestBatteryOptimization returns false when ignoring optimizations`() {
        every { mockPowerManager.isIgnoringBatteryOptimizations(any()) } returns true

        assertFalse(BatteryOptimizer.shouldRequestBatteryOptimization(context))
    }

    @Test
    fun `shouldRequestBatteryOptimization returns true when not ignoring optimizations`() {
        every { mockPowerManager.isIgnoringBatteryOptimizations(any()) } returns false

        assertTrue(BatteryOptimizer.shouldRequestBatteryOptimization(context))
    }

    @Test
    fun `checkAndNotify does nothing when already ignoring optimizations`() {
        every { mockPowerManager.isIgnoringBatteryOptimizations(any()) } returns true

        // 最適化無視済み → 通知送出スキップ → クラッシュなし
        BatteryOptimizer.checkAndNotify(context)
    }

    @Test
    fun `checkAndNotify does not crash when not ignoring optimizations`() {
        every { mockPowerManager.isIgnoringBatteryOptimizations(any()) } returns false

        // 通知チャネル作成・通知送出はNotificationManager=nullのためskip（try-catch内）
        BatteryOptimizer.checkAndNotify(context)
    }
}
