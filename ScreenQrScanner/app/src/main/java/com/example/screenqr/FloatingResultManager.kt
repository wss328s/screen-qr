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
 * 常驻悬浮窗：显示扫码内容列表（简洁版：复制 / 重置）。
 */
object FloatingResultManager {

    private var currentView: View? = null
    private var soundPool: SoundPool? = null
    private var beepId: Int = 0

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
        val text = currentListText(ctx)

        if (currentView == null) {
            val view = LayoutInflater.from(ctx).inflate(R.layout.floating_result, null)
            view.findViewById<TextView>(R.id.qrContent).text = text

            view.findViewById<Button>(R.id.copyBtn).setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("scan", currentFullSummary(ctx)))
                Toast.makeText(ctx, "已复制全部内容", Toast.LENGTH_SHORT).show()
            }
            view.findViewById<Button>(R.id.closeBtn).setOnClickListener {
                currentReset(ctx)
                update(ctx)
            }

            applyStyle(ctx, view)

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
            currentView?.let { applyStyle(ctx, it) }
            currentView?.findViewById<TextView>(R.id.qrContent)?.text = text
        }
    }

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
        if (SettingsManager.getMode(ctx) == SettingsManager.MODE_GAME)
            GameState.reset()
        else
            ScanHistory.reset()
    }

    // 应用悬浮窗样式
    private fun applyStyle(ctx: Context, view: View) {
        val style = SettingsManager.getFloatStyle(ctx)
        val alpha = SettingsManager.getFloatAlpha(ctx)
        val root = view.findViewById<LinearLayout>(R.id.floatRoot)
        val content = view.findViewById<TextView>(R.id.qrContent)

        val (bgColor, textColor) = when (style) {
            SettingsManager.FLOAT_STYLE_DARK -> 0xCC000000.toInt() to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_LIGHT -> 0xCCFFFFFF.toInt() to 0xFF000000.toInt()
            SettingsManager.FLOAT_STYLE_CLEAR -> 0x33000000 to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_PURPLE -> 0xCC6200EE.toInt() to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_BLUE -> 0xCC018786.toInt() to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_ORANGE -> 0xCCFF6D00.toInt() to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_GREEN -> 0xCC2E7D32.toInt() to 0xFFFFFFFF.toInt()
            else -> 0xCC000000.toInt() to 0xFFFFFFFF.toInt()
        }

        val alphaFactor = alpha / 100f
        val finalBg = adjustAlpha(bgColor, alphaFactor)

        root.setBackgroundColor(finalBg)
        content.setTextColor(textColor)
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

        // 播放音效（受音效开关控制）
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
