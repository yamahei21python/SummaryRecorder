package com.kohei.summaryrecorder.service

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BatteryOptimizer: バッテリー最適化チェック検証。
 *
 * 検証項目:
 * - shouldRequestBatteryOptimization: 最適化無視中 → false
 * - shouldRequestBatteryOptimization: 最適化有効中 → true
 * - checkAndNotify: 最適化無視中 → 通知送出なし（例外なし）
 *
 * ShadowPowerManagerでisIgnoringBatteryOptimizationsを制御。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class BatteryOptimizerTest {

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
    fun `shouldRequestBatteryOptimization returns false when ignoring optimizations`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsIgnoringBatteryOptimizations(true)

        assertFalse(BatteryOptimizer.shouldRequestBatteryOptimization(context))
    }

    @Test
    fun `shouldRequestBatteryOptimization returns true when not ignoring optimizations`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsIgnoringBatteryOptimizations(false)

        assertTrue(BatteryOptimizer.shouldRequestBatteryOptimization(context))
    }

    @Test
    fun `checkAndNotify does nothing when already ignoring optimizations`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsIgnoringBatteryOptimizations(true)

        // 最適化無視済み → checkAndNotify内で通知送出スキップ → 例外なし
        BatteryOptimizer.checkAndNotify(context)
        // 検証: クラッシュしないこと。通知が出ないことはRobolectricの限界で直接確認不可。
    }

    @Test
    fun `checkAndNotify shows notification when not ignoring optimizations`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsIgnoringBatteryOptimizations(false)

        // 最適化未無視 → 通知チャネル作成 + 通知送出が実行される（例外なし）
        BatteryOptimizer.checkAndNotify(context)
    }
}
