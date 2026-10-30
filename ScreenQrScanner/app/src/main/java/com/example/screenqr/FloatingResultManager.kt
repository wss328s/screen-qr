package com.example.screenqr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 常驻悬浮窗：显示扫码内容列表。
 */
object FloatingResultManager {

    private var currentView: View? = null
    private var soundPool: SoundPool? = null
    private var beepId: Int = 0
    private var loadedBeepId: Int = 0
    // 悬浮窗布局参数（拖拽移动时更新 x/y）
    private var layoutParams: WindowManager.LayoutParams? = null
    // 上次应用的样式键（样式+透明度），相同则跳过背景重建
    private var lastStyleKey: String? = null

    private fun initSound(ctx: Context) {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        // 加载完成后记录 id；加载完成前 beepId 保持 0，notifyRead 会静默跳过
        pool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) beepId = loadedBeepId
        }
        val id = pool.load(ctx, R.raw.beep, 1)
        loadedBeepId = id
        soundPool = pool
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
        val text = currentStatusText(ctx)

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

            // 应用样式
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
            layoutParams = params

            // 拖拽移动悬浮窗（只在真正拖动时消费事件，不影响按钮点击）
            attachDragListener(ctx, view, params)

            try {
                wm.addView(view, params)
                currentView = view
            } catch (e: Exception) {
            }
        } else {
            currentView?.let { applyStyle(ctx, it) }
            // 文本未变化时不重复赋值，避免无谓重绘
            val content = currentView?.findViewById<TextView>(R.id.qrContent)
            if (content != null && content.text?.toString() != text) {
                content.text = text
            }
        }
    }

    // 根据模式获取状态文本
    private fun currentStatusText(ctx: Context): String =
        if (SettingsManager.getMode(ctx) == SettingsManager.MODE_GAME)
            GameState.statusText()
        else
            ScanHistory.statusText()

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
        // 样式/透明度未变化时跳过重建背景，避免每次刷新都重绘（减少闪烁）
        val styleKey = "$style-$alpha"
        if (styleKey == lastStyleKey) return
        lastStyleKey = styleKey

        val root = view.findViewById<LinearLayout>(R.id.floatRoot)
        val content = view.findViewById<TextView>(R.id.qrContent)

        // 背景色和文字颜色
        val (bgColor, textColor) = when (style) {
            SettingsManager.FLOAT_STYLE_DARK -> 0xE6000000.toInt() to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_LIGHT -> 0xE6FFFFFF.toInt() to 0xFF101010.toInt()
            SettingsManager.FLOAT_STYLE_CLEAR -> 0x33000000 to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_PURPLE -> 0xE66200EE.toInt() to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_BLUE -> 0xE6018786.toInt() to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_ORANGE -> 0xE6FF6D00.toInt() to 0xFFFFFFFF.toInt()
            SettingsManager.FLOAT_STYLE_GREEN -> 0xE62E7D32.toInt() to 0xFFFFFFFF.toInt()
            else -> 0xE6000000.toInt() to 0xFFFFFFFF.toInt()
        }

        // 应用透明度（调整alpha通道）
        val alphaFactor = alpha / 100f
        val finalBg = adjustAlpha(bgColor, alphaFactor)

        // 用带圆角的 GradientDrawable 设置背景，保留圆角
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 16f)
            setColor(finalBg)
        }
        root.background = drawable
        content.setTextColor(textColor)

        // 标题栏与重置按钮文字颜色跟随样式（浅色样式用深色文字）
        val secondaryText = if (style == SettingsManager.FLOAT_STYLE_LIGHT) 0xFF404040.toInt() else 0xB0FFFFFF.toInt()
        view.findViewById<TextView>(R.id.floatTitle)?.setTextColor(secondaryText)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun dp(ctx: Context, value: Float): Float =
        value * ctx.resources.displayMetrics.density

    /**
     * 给悬浮窗根视图挂拖拽监听：按住空白处拖动可移动位置。
     * DOWN 时判断按点是否落在按钮上：落在按钮则放行（返回 false，让按钮 onClick 生效）；
     * 落在空白则消费（返回 true），后续 MOVE 更新位置，实现拖拽。
     */
    private fun attachDragListener(ctx: Context, view: View, params: WindowManager.LayoutParams) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        val copyBtn = view.findViewById<Button>(R.id.copyBtn)
        val closeBtn = view.findViewById<Button>(R.id.closeBtn)
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 按在按钮上 → 不消费，交给按钮处理点击
                    if (isPointOnView(copyBtn, event) || isPointOnView(closeBtn, event)) {
                        dragging = false
                        return@setOnTouchListener false
                    }
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop.toFloat() || abs(dy) > touchSlop.toFloat())) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> true
            }
        }
    }

    /** 判断触摸点是否落在指定视图的屏幕区域内 */
    private fun isPointOnView(target: View, event: MotionEvent): Boolean {
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        return x >= loc[0] && x <= loc[0] + target.width &&
            y >= loc[1] && y <= loc[1] + target.height
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
        layoutParams = null
        // 重置样式缓存：下次重建 view 时必须重新应用样式
        lastStyleKey = null
    }
}
