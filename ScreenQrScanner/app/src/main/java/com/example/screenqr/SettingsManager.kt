package com.example.screenqr

import android.content.Context

/**
 * 设置管理器：用SharedPreferences保存所有参数。
 */
object SettingsManager {

    private const val PREFS = "qr_settings"

    // 识别频率（帧/秒）
    const val KEY_FPS = "fps"
    const val FPS_1 = 1   // 1000ms
    const val FPS_2 = 2   // 500ms
    const val FPS_5 = 5   // 200ms
    const val FPS_10 = 10 // 100ms
    const val DEFAULT_FPS = FPS_5

    // 去重窗口（秒）
    const val KEY_DEDUP = "dedup"
    const val DEDUP_3 = 3
    const val DEDUP_5 = 5
    const val DEDUP_10 = 10
    const val DEFAULT_DEDUP = DEDUP_5

    // 识别增强
    const val KEY_ENHANCE = "enhance"
    const val ENHANCE_OFF = 0   // 关闭
    const val ENHANCE_STD = 1   // 标准（对比度增强）
    const val ENHANCE_STRONG = 2 // 增强（锐化+对比度）
    const val DEFAULT_ENHANCE = ENHANCE_STD

    // 分析精度（宽度px，0=原生）
    const val KEY_SCALE = "scale"
    const val SCALE_NATIVE = 0
    const val SCALE_800 = 800
    const val SCALE_1080 = 1080
    const val SCALE_1200 = 1200
    const val SCALE_1500 = 1500
    const val SCALE_2400 = 2400
    const val SCALE_3200 = 3200
    const val DEFAULT_SCALE = SCALE_NATIVE

    // 悬浮窗开关
    const val KEY_FLOAT = "float"
    const val DEFAULT_FLOAT = true

    // 震动开关
    const val KEY_VIBRATE = "vibrate"
    const val DEFAULT_VIBRATE = true

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFps(ctx: Context): Int = prefs(ctx).getInt(KEY_FPS, DEFAULT_FPS)
    fun setFps(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_FPS, v).apply()

    fun getDedup(ctx: Context): Int = prefs(ctx).getInt(KEY_DEDUP, DEFAULT_DEDUP)
    fun setDedup(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_DEDUP, v).apply()

    fun getEnhance(ctx: Context): Int = prefs(ctx).getInt(KEY_ENHANCE, DEFAULT_ENHANCE)
    fun setEnhance(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_ENHANCE, v).apply()

    fun getScale(ctx: Context): Int = prefs(ctx).getInt(KEY_SCALE, DEFAULT_SCALE)
    fun setScale(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_SCALE, v).apply()

    fun getFloat(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FLOAT, DEFAULT_FLOAT)
    fun setFloat(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_FLOAT, v).apply()

    fun getVibrate(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VIBRATE, DEFAULT_VIBRATE)
    fun setVibrate(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_VIBRATE, v).apply()

    // 工具方法：帧/秒 -> 间隔ms
    fun fpsToIntervalMs(fps: Int): Long = (1000.0 / fps).toLong()
}
