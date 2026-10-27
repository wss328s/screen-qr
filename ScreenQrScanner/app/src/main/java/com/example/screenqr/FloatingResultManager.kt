package com.example.screenqr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * 悬浮结果窗：小尺寸、底部弹出、2.5秒自动消失，不挡屏幕。
 */
object FloatingResultManager {

    private var currentView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideAllInternal() }

    fun show(ctx: Context, text: String) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hideAllInternal()

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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        try {
            wm.addView(view, params)
            currentView = view
            vibrate(ctx)
            // 2.5秒后自动消失，不挡下一个二维码
            handler.postDelayed(autoHideRunnable, 2500)
        } catch (e: Exception) {
            // 没授权悬浮窗时静默失败
        }
    }

    fun hideAll(ctx: Context) {
        handler.removeCallbacks(autoHideRunnable)
        hideAllInternal()
    }

    private fun hideAllInternal() {
        currentView?.let {
            val wm = it.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        currentView = null
    }

    private fun vibrate(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(80)
        }
    }
}
