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
 * 常驻悬浮条：显示比赛导航状态（当前岔路、已读码、推荐方向）。
 */
object FloatingResultManager {

    private var currentView: View? = null

    fun update(ctx: Context) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val text = GameState.statusText()

        if (currentView == null) {
            val view = LayoutInflater.from(ctx).inflate(R.layout.floating_result, null)
            view.findViewById<TextView>(R.id.qrContent).text = text
            view.findViewById<Button>(R.id.copyBtn).setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("game", GameState.fullSummary()))
                Toast.makeText(ctx, "已复制全部结果", Toast.LENGTH_SHORT).show()
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
            } catch (e: Exception) {
            }
        } else {
            currentView?.findViewById<TextView>(R.id.qrContent)?.text = text
        }
    }

    fun notifyRead(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(80)
        }
    }

    fun hideAll(ctx: Context) {
        currentView?.let {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        currentView = null
    }
}
