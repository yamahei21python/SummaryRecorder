package com.kohei.summaryrecorder.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * バッテリー最適化マネージャー。
 * RecordingServiceからバッテリー関連ロジックを分離。
 *
 * 設定画面を直接開くのではなく、通知でユーザーに促す。
 */
object BatteryOptimizer {

    private const val CHANNEL_ID = "battery_optimization_channel"
    private const val NOTIFICATION_ID = 100

    /**
     * バッテリー最適化設定が必要な場合、通知でユーザーに促す。
     * onCreate()で呼び出す。
     */
    fun checkAndNotify(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                createNotificationChannel(context)
                showNotification(context)
            }
        } catch (_: Exception) {
            // テスト環境やSettings未サポート端末では安全に無視
        }
    }

    /**
     * バッテリー最適化が設定済みかチェック。
     */
    fun shouldRequestBatteryOptimization(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            !pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "バッテリー最適化", NotificationManager.IMPORTANCE_LOW
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun showNotification(context: Context) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        ).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("バッテリー最適化の設定")
            .setContentText("録音が安定するよう、バッテリー最適化を無効にしてください")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
