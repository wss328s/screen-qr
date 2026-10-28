package com.example.screenqr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 常驻悬浮窗：显示扫码内容列表。
 */
object FloatingResultManager {

    private var currentView: View? = null
    private var soundPool: SoundPool? = null
    private var beepId: Int = 0

    // 疑似码状态（短时间内有效）
    @Volatile private var suspectedAt: Long = 0
    fun markSuspect() { suspectedAt = System.currentTimeMillis() }
    private fun isSuspected(): Boolean = System.currentTimeMillis() - suspectedAt < 1500

    private fun initSound(ctx: Context) {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        beepId = soundPool?.load(ctx, R.raw.beep, 1) ?: 0
    }

    // 预加载音效（服务启动时调用）
    fun preloadSound(ctx: Context) {
        try { initSound(ctx) } catch (e: Exception) {}
    }

    fun update(ctx: Context) {
        if (!SettingsManager.getFloat(ctx)) {
            hideAll(ctx)
            return
        }

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (currentView == null) {
            val view = LayoutInflater.from(ctx).inflate(R.layout.floating_result, null)

            // 清除按钮
            view.findViewById<TextView>(R.id.clearBtn).setOnClickListener {
                currentReset(ctx)
                update(ctx)
            }

            // 长按内容区复制全部
            val content = view.findViewById<TextView>(R.id.qrContent)
            content.setOnLongClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("scan", currentFullSummary(ctx)))
                Toast.makeText(ctx, "已复制全部内容", Toast.LENGTH_SHORT).show()
                true
            }

            applyStyle(ctx, view)
            fillContent(ctx, view)

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
            currentView?.let {
                applyStyle(ctx, it)
                fillContent(ctx, it)
            }
        }
    }

    // 填充状态、提示、列表
    private fun fillContent(ctx: Context, view: View) {
        val count = currentCount(ctx)
        val suspect = isSuspected()
        val dot = view.findViewById<View>(R.id.statusDot)
        val statusText = view.findViewById<TextView>(R.id.statusText)
        val hint = view.findViewById<TextView>(R.id.hintText)
        val content = view.findViewById<TextView>(R.id.qrContent)

        if (suspect) {
            // 疑似码未解析（橙色）
            dot.setBackgroundColor(0xFFFFA726.toInt())
            statusText.setTextColor(0xFFFFA726.toInt())
            statusText.text = "疑似码未解析"
            hint.text = "可调整距离/角度后重试"
        } else if (count == 0) {
            // 等待（绿色）
            dot.setBackgroundColor(0xFF4CAF50.toInt())
            statusText.setTextColor(0xFF4CAF50.toInt())
            statusText.text = "监听中 · 等待二维码"
            hint.text = "扫描中，请将二维码放入画面"
        } else {
            // 已记录（绿色）
            dot.setBackgroundColor(0xFF4CAF50.toInt())
            statusText.setTextColor(0xFF4CAF50.toInt())
            statusText.text = "已记录 $count 个"
            hint.text = "扫描中，请将二维码放入画面"
        }

        content.text = currentListText(ctx)
    }

    // 根据模式获取数量
    private fun currentCount(ctx: Context): Int =
        if (SettingsManager.getMode(ctx) == SettingsManager.MODE_GAME)
            GameState.totalRead
        else
            ScanHistory.count

    // 根据模式获取列表文本
    private fun currentListText(ctx: Context): String =
        if (SettingsManager.getMode(ctx) == SettingsManager.MODE_GAME)
            GameState.listText()
        else
            ScanHistory.listText()

    // 根据模式获取完整摘要
    private fun currentFullSummary(ctx: Context): String =
        if (SettingsManager.getMode(ctx) == SettingsManager.MODE_GAME)
            GameState.fullSummary()
        else
            ScanHistory.fullSummary()

    // 根据模式重置
    private fun currentReset(ctx: Context) {
        suspectedAt = 0L
        if (SettingsManager.getMode(ctx) == SettingsManager.MODE_GAME)
            GameState.reset()
        else
            ScanHistory.reset()
    }

    // 应用悬浮窗样式（GradientDrawable，保持圆角）
    private fun applyStyle(ctx: Context, view: View) {
        val style = SettingsManager.getFloatStyle(ctx)
        val alpha = SettingsManager.getFloatAlpha(ctx)
        val root = view.findViewById<LinearLayout>(R.id.floatRoot)
        val content = view.findViewById<TextView>(R.id.qrContent)
        val hint = view.findViewById<TextView>(R.id.hintText)

        val bgColor = when (style) {
            SettingsManager.FLOAT_STYLE_DARK -> 0xE61F2937.toInt()
            SettingsManager.FLOAT_STYLE_LIGHT -> 0xE6FFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_CLEAR -> 0x401F2937
            SettingsManager.FLOAT_STYLE_PURPLE -> 0xE66200EE.toInt()
            SettingsManager.FLOAT_STYLE_BLUE -> 0xE60E7490.toInt()
            SettingsManager.FLOAT_STYLE_ORANGE -> 0xE6EA580C.toInt()
            SettingsManager.FLOAT_STYLE_GREEN -> 0xE6166534.toInt()
            else -> 0xE61F2937.toInt()
        }

        val isLight = style == SettingsManager.FLOAT_STYLE_LIGHT
        val alphaFactor = alpha / 100f
        val finalBg = adjustAlpha(bgColor, alphaFactor)

        val gd = android.graphics.drawable.GradientDrawable()
        gd.cornerRadius = 28f * ctx.resources.displayMetrics.density
        gd.setColor(finalBg)
        root.background = gd

        val mainText = if (isLight) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        val subText = if (isLight) 0xFF444444.toInt() else 0xFFD1D5DB.toInt()
        hint.setTextColor(mainText)
        content.setTextColor(subText)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    fun notifyRead(ctx: Context) {
        // 震动
        if (SettingsManager.getVibrate(ctx)) {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(80)
            }
        }

        // 播放音效（SoundPool，更快）
        if (SettingsManager.getSound(ctx)) {
            try {
                initSound(ctx)
                if (beepId != 0) {
                    soundPool?.play(beepId, 1f, 1f, 1, 0, 1f)
                }
            } catch (e: Exception) {
            }
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
