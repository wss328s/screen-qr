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
 * 常驻小悬浮条：识别到二维码直接更新内容，不弹窗不消失。
 */
object FloatingResultManager {

    private var currentView: View? = null
    private var lastText: String = ""

    fun show(ctx: Context, text: String) {
        // 内容没变就不更新，避免闪烁
        if (text == lastText && currentView != null) return
        lastText = text

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (currentView == null) {
            // 创建常驻悬浮条
            val view = LayoutInflater.from(ctx).inflate(R.layout.floating_result, null)
            view.findViewById<TextView>(R.id.qrContent).text = text
            view.findViewById<Button>(R.id.copyBtn).setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("qr", text))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            }
            view.findViewById<Button>(R.id.closeBtn).setOnClickListener { hideAll(ctx) }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 10
                y = 100
            }

            try {
                wm.addView(view, params)
                currentView = view
                vibrate(ctx)
            } catch (e: Exception) {
                // 没授权悬浮窗时静默失败
            }
        } else {
            // 已存在，直接更新文字
            currentView?.findViewById<TextView>(R.id.qrContent)?.text = text
            vibrate(ctx)
        }
    }

    fun hideAll(ctx: Context) {
        lastText = ""
        currentView?.let {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        currentView = null
    }

    private fun vibrate(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(60)
        }
    }
}
