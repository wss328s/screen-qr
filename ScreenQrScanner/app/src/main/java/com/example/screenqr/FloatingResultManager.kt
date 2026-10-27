package com.example.screenqr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮结果窗：识别到二维码内容后弹出，可复制、可关闭。
 */
object FloatingResultManager {

    private var currentView: View? = null

    fun show(ctx: Context, text: String) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 先移除旧的，避免堆一串
        hideAll(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.floating_result, null)
        view.findViewById<TextView>(R.id.qrContent).text = text
        view.findViewById<Button>(R.id.copyBtn).setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qr", text))
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            hideAll(ctx)
        }
        view.findViewById<Button>(R.id.closeBtn).setOnClickListener { hideAll(ctx) }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        try {
            wm.addView(view, params)
            currentView = view
            vibrate(ctx)
        } catch (e: Exception) {
            // 没授权悬浮窗时静默失败
        }
    }

    fun hideAll(ctx: Context) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        currentView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        currentView = null
    }

    private fun vibrate(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMBIENT_DEFAULT))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(80)
        }
    }
}
