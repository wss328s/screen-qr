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
 * 常驻悬浮窗：显示比赛导航状态 + 红色画框。
 */
object FloatingResultManager {

    private var currentView: View? = null
    private var overlayView: QrOverlayView? = null

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
            view.findViewById<Button>(R.id.closeBtn).setOnClickListener {
                GameState.reset()
                update(ctx)
            }

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

            // 加全屏画框View
            if (overlayView == null) {
                val overlay = QrOverlayView(ctx)
                val overlayParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
                try {
                    wm.addView(overlay, overlayParams)
                    overlayView = overlay
                } catch (e: Exception) {
                }
            }
        } else {
            currentView?.findViewById<TextView>(R.id.qrContent)?.text = text
        }
    }

    /**
     * 更新画框位置。
     * @param points ZXing返回的二维码角点坐标（在缩放后的bitmap坐标系里）
     * @param bitmapW 缩放后的bitmap宽度
     * @param bitmapH 缩放后的bitmap高度
     * @param screenW 屏幕实际宽度
     * @param screenH 屏幕实际高度
     */
    fun updateRect(points: FloatArray?, bitmapW: Int, bitmapH: Int, screenW: Int, screenH: Int) {
        val overlay = overlayView ?: return
        if (points == null || points.size < 4) {
            overlay.clearRect()
            return
        }

        // 从bitmap坐标换算到屏幕坐标
        val scaleX = screenW.toFloat() / bitmapW
        val scaleY = screenH.toFloat() / bitmapH

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        // points是[x1,y1,x2,y2,x3,y3...]
        for (i in 0 until points.size step 2) {
            val x = points[i] * scaleX
            val y = points[i + 1] * scaleY
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        // 往外扩一点
        val padding = 20f
        overlay.setRect(minX - padding, minY - padding, maxX + padding, maxY + padding)
    }

    fun clearRect() {
        overlayView?.clearRect()
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

        overlayView?.let {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }
}
