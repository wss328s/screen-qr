package com.example.screenqr

import android.content.Context

/**
 * 设置管理器：用SharedPreferences保存所有参数。
 */
object SettingsManager {

    private const val PREFS = "qr_settings"

    // 工作模式
    const val KEY_MODE = "mode"
    const val MODE_SCAN = 0    // 纯扫码
    const val MODE_GAME = 1    // 比赛导航
    const val DEFAULT_MODE = MODE_SCAN

    // 识别频率（帧/秒）
    const val KEY_FPS = "fps"
    const val FPS_1 = 1   // 1000ms
    const val FPS_2 = 2   // 500ms
    const val FPS_5 = 5   // 200ms
    const val FPS_7_5 = 7 // 133ms (7.5帧/秒)
    const val FPS_10 = 10 // 100ms
    const val FPS_15 = 15 // 66ms
    const val FPS_20 = 20 // 50ms
    const val FPS_25 = 25 // 40ms
    const val FPS_30 = 30 // 33ms
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

    // 识别区域 ROI（只扫二维码会出现的区域，像素越少越快）
    const val KEY_REGION = "region"
    const val REGION_FULL = 0       // 全屏（含OSD，最慢）
    const val REGION_VIDEO = 1      // 图传安全区，约77%（默认，推荐）
    const val REGION_CENTER = 2     // 中央区域，约56%
    const val REGION_LOWER = 3      // 下方中央，约43%
    const val DEFAULT_REGION = REGION_VIDEO

    // 悬浮窗开关
    const val KEY_FLOAT = "float"
    const val DEFAULT_FLOAT = true

    // 震动开关
    const val KEY_VIBRATE = "vibrate"
    const val DEFAULT_VIBRATE = true

    // 音效开关
    const val KEY_SOUND = "sound"
    const val DEFAULT_SOUND = true

    // 活码（网络二维码）解析：扫到草料等活码时联网读取后台真实内容
    const val KEY_LIVE = "live_code"
    const val DEFAULT_LIVE = true

    // 开机自启开关
    const val KEY_BOOT = "boot_auto_start"
    const val DEFAULT_BOOT = true

    // 悬浮窗透明度（0-100）
    const val KEY_FLOAT_ALPHA = "float_alpha"
    const val DEFAULT_FLOAT_ALPHA = 100

    // 悬浮窗样式
    const val KEY_FLOAT_STYLE = "float_style"
    const val FLOAT_STYLE_DARK = 0       // 深色
    const val FLOAT_STYLE_LIGHT = 1      // 浅色
    const val FLOAT_STYLE_CLEAR = 2      // 透明
    const val FLOAT_STYLE_PURPLE = 3     // 紫色渐变
    const val FLOAT_STYLE_BLUE = 4       // 蓝色科技
    const val FLOAT_STYLE_ORANGE = 5     // 橙色活力
    const val FLOAT_STYLE_GREEN = 6      // 绿色护眼
    const val DEFAULT_FLOAT_STYLE = FLOAT_STYLE_DARK

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): Int = prefs(ctx).getInt(KEY_MODE, DEFAULT_MODE)
    fun setMode(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_MODE, v).apply()

    fun getFps(ctx: Context): Int = prefs(ctx).getInt(KEY_FPS, DEFAULT_FPS)
    fun setFps(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_FPS, v).apply()

    fun getDedup(ctx: Context): Int = prefs(ctx).getInt(KEY_DEDUP, DEFAULT_DEDUP)
    fun setDedup(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_DEDUP, v).apply()

    fun getEnhance(ctx: Context): Int = prefs(ctx).getInt(KEY_ENHANCE, DEFAULT_ENHANCE)
    fun setEnhance(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_ENHANCE, v).apply()

    fun getScale(ctx: Context): Int = prefs(ctx).getInt(KEY_SCALE, DEFAULT_SCALE)
    fun setScale(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_SCALE, v).apply()

    fun getRegion(ctx: Context): Int = prefs(ctx).getInt(KEY_REGION, DEFAULT_REGION)
    fun setRegion(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_REGION, v).apply()

    /** 返回 ROI 归一化边界 [left, top, right, bottom]（0..1） */
    fun getRegionBounds(ctx: Context): FloatArray = when (getRegion(ctx)) {
        REGION_FULL -> floatArrayOf(0f, 0f, 1f, 1f)
        REGION_CENTER -> floatArrayOf(0.10f, 0.15f, 0.90f, 0.85f)
        REGION_LOWER -> floatArrayOf(0.15f, 0.30f, 0.85f, 0.92f)
        else -> floatArrayOf(0.03f, 0.09f, 0.97f, 0.91f) // REGION_VIDEO
    }

    fun getFloat(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FLOAT, DEFAULT_FLOAT)
    fun setFloat(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_FLOAT, v).apply()

    fun getVibrate(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VIBRATE, DEFAULT_VIBRATE)
    fun setVibrate(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_VIBRATE, v).apply()

    fun getSound(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SOUND, DEFAULT_SOUND)
    fun setSound(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_SOUND, v).apply()

    fun getLiveCode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LIVE, DEFAULT_LIVE)
    fun setLiveCode(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_LIVE, v).apply()

    fun getBoot(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BOOT, DEFAULT_BOOT)
    fun setBoot(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_BOOT, v).apply()

    fun getFloatAlpha(ctx: Context): Int = prefs(ctx).getInt(KEY_FLOAT_ALPHA, DEFAULT_FLOAT_ALPHA)
    fun setFloatAlpha(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_FLOAT_ALPHA, v).apply()

    fun getFloatStyle(ctx: Context): Int = prefs(ctx).getInt(KEY_FLOAT_STYLE, DEFAULT_FLOAT_STYLE)
    fun setFloatStyle(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_FLOAT_STYLE, v).apply()

    // 工具方法：帧/秒 -> 间隔ms
    fun fpsToIntervalMs(fps: Int): Long = when (fps) {
        FPS_7_5 -> 133L
        else -> (1000.0 / fps).toLong()
    }
}
