package com.example.screenqr

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var overlayPermValue: TextView
    private lateinit var modeValue: TextView
    private lateinit var fpsValue: TextView
    private lateinit var dedupValue: TextView
    private lateinit var enhanceValue: TextView
    private lateinit var scaleValue: TextView
    private lateinit var regionValue: TextView
    private lateinit var floatValue: TextView
    private lateinit var floatStyleValue: TextView
    private lateinit var floatAlphaValue: TextView
    private lateinit var vibrateValue: TextView
    private lateinit var floatSwitch: MaterialSwitch
    private lateinit var vibrateSwitch: MaterialSwitch
    private lateinit var soundSwitch: MaterialSwitch
    private lateinit var bootSwitch: MaterialSwitch
    private lateinit var bootValue: TextView
    private lateinit var soundValue: TextView
    private lateinit var deviceInfo: TextView

    companion object {
        private const val REQ_OVERLAY = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        overlayPermValue = findViewById(R.id.overlayPermValue)
        modeValue = findViewById(R.id.modeValue)
        fpsValue = findViewById(R.id.fpsValue)
        dedupValue = findViewById(R.id.dedupValue)
        enhanceValue = findViewById(R.id.enhanceValue)
        scaleValue = findViewById(R.id.scaleValue)
        regionValue = findViewById(R.id.regionValue)
        floatValue = findViewById(R.id.floatValue)
        floatStyleValue = findViewById(R.id.floatStyleValue)
        floatAlphaValue = findViewById(R.id.floatAlphaValue)
        vibrateValue = findViewById(R.id.vibrateValue)
        floatSwitch = findViewById(R.id.floatSwitch)
        vibrateSwitch = findViewById(R.id.vibrateSwitch)
        soundSwitch = findViewById(R.id.soundSwitch)
        soundValue = findViewById(R.id.soundValue)
        bootSwitch = findViewById(R.id.bootSwitch)
        bootValue = findViewById(R.id.bootValue)
        deviceInfo = findViewById(R.id.deviceInfo)

        // 悬浮窗权限申请
        findViewById<MaterialCardView>(R.id.overlayPermCard).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }

        findViewById<MaterialCardView>(R.id.modeCard).setOnClickListener { showModeDialog() }
        findViewById<MaterialCardView>(R.id.fpsCard).setOnClickListener { showFpsDialog() }
        findViewById<MaterialCardView>(R.id.dedupCard).setOnClickListener { showDedupDialog() }
        findViewById<MaterialCardView>(R.id.enhanceCard).setOnClickListener { showEnhanceDialog() }
        findViewById<MaterialCardView>(R.id.scaleCard).setOnClickListener { showScaleDialog() }
        findViewById<MaterialCardView>(R.id.regionCard).setOnClickListener { showRegionDialog() }
        findViewById<MaterialCardView>(R.id.floatStyleCard).setOnClickListener { showFloatStyleDialog() }

        // 透明度滑块
        val alphaSeek = findViewById<SeekBar>(R.id.floatAlphaSeek)
        alphaSeek.progress = SettingsManager.getFloatAlpha(this)
        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                SettingsManager.setFloatAlpha(this@SettingsActivity, progress)
                floatAlphaValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        floatSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setFloat(this, checked)
            floatValue.text = if (checked) "已开启" else "已关闭"
        }

        vibrateSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setVibrate(this, checked)
            vibrateValue.text = if (checked) "已开启" else "已关闭"
        }

        soundSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setSound(this, checked)
            soundValue.text = if (checked) "已开启" else "已关闭"
        }

        bootSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setBoot(this, checked)
            bootValue.text = if (checked) "已开启" else "已关闭"
        }

        refreshTexts()
    }

    override fun onResume() {
        super.onResume()
        refreshTexts()
    }

    private fun refreshTexts() {
        // 悬浮窗权限状态
        overlayPermValue.text = if (Settings.canDrawOverlays(this)) "已授予" else "未授予"

        // 模式
        val mode = SettingsManager.getMode(this)
        modeValue.text = if (mode == SettingsManager.MODE_GAME) "比赛导航" else "纯扫码"

        // 频率
        val fps = SettingsManager.getFps(this)
        fpsValue.text = when (fps) {
            SettingsManager.FPS_1 -> "1 帧/秒（1000ms）"
            SettingsManager.FPS_2 -> "2 帧/秒（500ms）"
            SettingsManager.FPS_5 -> "5 帧/秒（200ms）"
            SettingsManager.FPS_7_5 -> "7.5 帧/秒（133ms）"
            SettingsManager.FPS_10 -> "10 帧/秒（100ms）"
            SettingsManager.FPS_15 -> "15 帧/秒（66ms）"
            SettingsManager.FPS_20 -> "20 帧/秒（50ms）"
            SettingsManager.FPS_25 -> "25 帧/秒（40ms）"
            SettingsManager.FPS_30 -> "30 帧/秒（33ms）"
            else -> "$fps 帧/秒"
        }

        // 去重
        val dedup = SettingsManager.getDedup(this)
        dedupValue.text = "$dedup 秒"

        // 增强
        val enhance = SettingsManager.getEnhance(this)
        enhanceValue.text = when (enhance) {
            SettingsManager.ENHANCE_OFF -> "关闭"
            SettingsManager.ENHANCE_STD -> "标准（对比度增强）"
            SettingsManager.ENHANCE_STRONG -> "增强（锐化+对比度）"
            else -> "未知"
        }

        // 精度
        val scale = SettingsManager.getScale(this)
        scaleValue.text = when (scale) {
            SettingsManager.SCALE_NATIVE -> "原生分辨率（不缩放）"
            else -> "${scale}px 宽"
        }

        // 识别区域
        val region = SettingsManager.getRegion(this)
        regionValue.text = when (region) {
            SettingsManager.REGION_FULL -> "全屏（含OSD，最慢）"
            SettingsManager.REGION_CENTER -> "中央区域（约56%）"
            SettingsManager.REGION_LOWER -> "下方中央（约43%）"
            else -> "图传安全区（约77%，推荐）"
        }

        // 悬浮窗
        val floatOn = SettingsManager.getFloat(this)
        floatSwitch.isChecked = floatOn
        floatValue.text = if (floatOn) "已开启" else "已关闭"

        // 开机自启
        val bootOn = SettingsManager.getBoot(this)
        bootSwitch.isChecked = bootOn
        bootValue.text = if (bootOn) "已开启" else "已关闭"

        // 样式
        val style = SettingsManager.getFloatStyle(this)
        floatStyleValue.text = when (style) {
            SettingsManager.FLOAT_STYLE_DARK -> "深色"
            SettingsManager.FLOAT_STYLE_LIGHT -> "浅色"
            SettingsManager.FLOAT_STYLE_CLEAR -> "透明"
            SettingsManager.FLOAT_STYLE_PURPLE -> "紫色渐变"
            SettingsManager.FLOAT_STYLE_BLUE -> "蓝色科技"
            SettingsManager.FLOAT_STYLE_ORANGE -> "橙色活力"
            SettingsManager.FLOAT_STYLE_GREEN -> "绿色护眼"
            else -> "未知"
        }

        // 透明度
        val alpha = SettingsManager.getFloatAlpha(this)
        floatAlphaValue.text = "$alpha%"

        // 震动
        val vibrateOn = SettingsManager.getVibrate(this)
        vibrateSwitch.isChecked = vibrateOn
        vibrateValue.text = if (vibrateOn) "已开启" else "已关闭"

        // 音效
        val soundOn = SettingsManager.getSound(this)
        soundSwitch.isChecked = soundOn
        soundValue.text = if (soundOn) "已开启" else "已关闭"

        // 设备信息
        deviceInfo.text = buildDeviceInfo()
    }

    private fun buildDeviceInfo(): String {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val density = metrics.density
        val densityDpi = metrics.densityDpi

        return buildString {
            append("设备型号：${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android版本：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("屏幕分辨率：${screenW} × ${screenH}\n")
            append("屏幕密度：${density}x (${densityDpi}dpi)\n")
            append("处理器：${Build.HARDWARE}\n")
            append("系统版本：${Build.DISPLAY}")
        }
    }

    private fun showModeDialog() {
        val items = arrayOf("纯扫码", "比赛导航（4岔路×3码比大小）")
        val values = intArrayOf(SettingsManager.MODE_SCAN, SettingsManager.MODE_GAME)
        var checked = values.indexOf(SettingsManager.getMode(this))
        if (checked < 0) checked = 0

        AlertDialog.Builder(this)
            .setTitle("识别模式")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                SettingsManager.setMode(this, values[which])
                dialog.dismiss()
                refreshTexts()
            }
            .show()
    }

    private fun showFpsDialog() {
        val items = arrayOf(
            "1 帧/秒（1000ms）", "2 帧/秒（500ms）", "5 帧/秒（200ms）",
            "7.5 帧/秒（133ms）", "10 帧/秒（100ms）",
            "15 帧/秒（66ms）", "20 帧/秒（50ms）",
            "25 帧/秒（40ms）", "30 帧/秒（33ms）"
        )
        val values = intArrayOf(
            SettingsManager.FPS_1, SettingsManager.FPS_2, SettingsManager.FPS_5,
            SettingsManager.FPS_7_5, SettingsManager.FPS_10,
            SettingsManager.FPS_15, SettingsManager.FPS_20,
            SettingsManager.FPS_25, SettingsManager.FPS_30
        )
        var checked = values.indexOf(SettingsManager.getFps(this))
        if (checked < 0) checked = 2

        AlertDialog.Builder(this)
            .setTitle("识别频率")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                SettingsManager.setFps(this, values[which])
                dialog.dismiss()
                refreshTexts()
            }
            .show()
    }

    private fun showDedupDialog() {
        val items = arrayOf("3 秒", "5 秒", "10 秒")
        val values = intArrayOf(SettingsManager.DEDUP_3, SettingsManager.DEDUP_5, SettingsManager.DEDUP_10)
        var checked = values.indexOf(SettingsManager.getDedup(this))
        if (checked < 0) checked = 1

        AlertDialog.Builder(this)
            .setTitle("去重窗口")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                SettingsManager.setDedup(this, values[which])
                dialog.dismiss()
                refreshTexts()
            }
            .show()
    }

    private fun showEnhanceDialog() {
        val items = arrayOf("关闭", "标准（对比度增强）", "增强（锐化+对比度）")
        val values = intArrayOf(SettingsManager.ENHANCE_OFF, SettingsManager.ENHANCE_STD, SettingsManager.ENHANCE_STRONG)
        var checked = values.indexOf(SettingsManager.getEnhance(this))
        if (checked < 0) checked = 1

        AlertDialog.Builder(this)
            .setTitle("识别增强（对焦不实/小码）")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                SettingsManager.setEnhance(this, values[which])
                dialog.dismiss()
                refreshTexts()
            }
            .show()
    }

    private fun showScaleDialog() {
        val items = arrayOf(
            "原生分辨率（不缩放，小码必看）",
            "3200px 宽", "2400px 宽", "1500px 宽",
            "1200px 宽", "1080px 宽", "800px 宽（快）"
        )
        val values = intArrayOf(
            SettingsManager.SCALE_NATIVE,
            SettingsManager.SCALE_3200, SettingsManager.SCALE_2400,
            SettingsManager.SCALE_1500, SettingsManager.SCALE_1200,
            SettingsManager.SCALE_1080, SettingsManager.SCALE_800
        )
        var checked = values.indexOf(SettingsManager.getScale(this))
        if (checked < 0) checked = 0

        AlertDialog.Builder(this)
            .setTitle("分析精度（小码必看）")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                SettingsManager.setScale(this, values[which])
                dialog.dismiss()
                refreshTexts()
            }
            .show()
    }

    private fun showRegionDialog() {
        val items = arrayOf(
            "图传安全区（约77%，推荐，避开OSD）",
            "中央区域（约56%，更快）",
            "下方中央（约43%，最快）",
            "全屏（含OSD，最慢）"
        )
        val values = intArrayOf(
            SettingsManager.REGION_VIDEO,
            SettingsManager.REGION_CENTER,
            SettingsManager.REGION_LOWER,
            SettingsManager.REGION_FULL
        )
        var checked = values.indexOf(SettingsManager.getRegion(this))
        if (checked < 0) checked = 0

        AlertDialog.Builder(this)
            .setTitle("识别区域（区域越小越快）")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                SettingsManager.setRegion(this, values[which])
                dialog.dismiss()
                refreshTexts()
            }
            .show()
    }

    private fun showFloatStyleDialog() {
        val items = arrayOf(
            "深色", "浅色", "透明",
            "紫色渐变", "蓝色科技", "橙色活力", "绿色护眼"
        )
        val values = intArrayOf(
            SettingsManager.FLOAT_STYLE_DARK,
            SettingsManager.FLOAT_STYLE_LIGHT,
            SettingsManager.FLOAT_STYLE_CLEAR,
            SettingsManager.FLOAT_STYLE_PURPLE,
            SettingsManager.FLOAT_STYLE_BLUE,
            SettingsManager.FLOAT_STYLE_ORANGE,
            SettingsManager.FLOAT_STYLE_GREEN
        )
        var checked = values.indexOf(SettingsManager.getFloatStyle(this))
        if (checked < 0) checked = 0

        AlertDialog.Builder(this)
            .setTitle("悬浮窗样式")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                SettingsManager.setFloatStyle(this, values[which])
                dialog.dismiss()
                refreshTexts()
            }
            .show()
    }
}
