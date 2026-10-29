package com.example.screenqr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 开机自启：收到开机广播后直接打开APP，自动弹截屏授权窗。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // 未开启开机自启则不处理
        if (!SettingsManager.getBoot(context)) return

        // 直接尝试打开MainActivity（打开后会自动请求权限）
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // 尝试直接启动（Android 10+ 后台启动Activity可能被限制，被限制了就发通知兜底）
        try {
            context.startActivity(openIntent)
        } catch (e: Exception) {
            // 被系统阻止了，发通知让用户点
            val channelId = "screen_qr_boot"
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "开机自启", NotificationManager.IMPORTANCE_HIGH)
                nm.createNotificationChannel(channel)
            }

            val pi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val noti = NotificationCompat.Builder(context, channelId)
                .setContentTitle("屏幕二维码识别")
                .setContentText("点击开始扫描")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            nm.notify(2001, noti)
        }
    }
}
