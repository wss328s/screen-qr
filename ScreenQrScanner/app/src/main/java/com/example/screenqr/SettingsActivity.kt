package com.example.screenqr

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var fpsText: TextView
    private lateinit var dedupText: TextView
    private lateinit var enhanceText: TextView
    private lateinit var scaleText: TextView
    private lateinit var floatText: TextView
    private lateinit var vibrateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "设置"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val fpsBtn = Button(this).apply {
            text = "识别频率"
            setOnClickListener { showFpsDialog() }
        }
        layout.addView(fpsBtn)
        fpsText = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        layout.addView(fpsText)

        val dedupBtn = Button(this).apply {
            text = "去重窗口"
            setOnClickListener { showDedupDialog() }
        }
        layout.addView(dedupBtn)
        dedupText = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        layout.addView(dedupText)

        val enhanceBtn = Button(this).apply {
            text = "识别增强"
            setOnClickListener { showEnhanceDialog() }
        }
        layout.addView(enhanceBtn)
        enhanceText = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        layout.addView(enhanceText)

        val scaleBtn = Button(this).apply {
            text = "分析精度"
            setOnClickListener { showScaleDialog() }
        }
        layout.addView(scaleBtn)
        scaleText = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        layout.addView(scaleText)

        val floatBtn = Button(this).apply {
            text = "悬浮窗"
            setOnClickListener { toggleFloat() }
        }
        layout.addView(floatBtn)
        floatText = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        layout.addView(floatText)

        val vibrateBtn = Button(this).apply {
            text = "震动反馈"
            setOnClickListener { toggleVibrate() }
        }
        layout.addView(vibrateBtn)
        vibrateText = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        layout.addView(vibrateText)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)

        refreshTexts()
    }

    private fun refreshTexts() {
        val fps = SettingsManager.getFps(this)
        fpsText.text = when (fps) {
            SettingsManager.FPS_1 -> "识别频率：1 帧/秒"
            SettingsManager.FPS_2 -> "识别频率：2 帧/秒"
            SettingsManager.FPS_5 -> "识别频率：5 帧/秒"
            SettingsManager.FPS_7_5 -> "识别频率：7.5 帧/秒"
            SettingsManager.FPS_10 -> "识别频率：10 帧/秒"
            else -> "识别频率：$fps 帧/秒"
        }

        val dedup = SettingsManager.getDedup(this)
        dedupText.text = "去重窗口：$dedup 秒"

        val enhance = SettingsManager.getEnhance(this)
        enhanceText.text = when (enhance) {
            SettingsManager.ENHANCE_OFF -> "识别增强：关闭"
            SettingsManager.ENHANCE_STD -> "识别增强：标准"
            SettingsManager.ENHANCE_STRONG -> "识别增强：增强"
            else -> "识别增强：未知"
        }

        val scale = SettingsManager.getScale(this)
        scaleText.text = when (scale) {
            SettingsManager.SCALE_NATIVE -> "分析精度：原生分辨率"
            else -> "分析精度：${scale}px 宽"
        }

        floatText.text = "悬浮窗：" + if (SettingsManager.getFloat(this)) "开" else "关"
        vibrateText.text = "震动反馈：" + if (SettingsManager.getVibrate(this)) "开" else "关"
    }

    private fun showFpsDialog() {
        val items = arrayOf("1 帧/秒（1000ms）", "2 帧/秒（500ms）", "5 帧/秒（200ms）", "7.5 帧/秒（133ms）", "10 帧/秒（100ms）")
        val values = intArrayOf(SettingsManager.FPS_1, SettingsManager.FPS_2, SettingsManager.FPS_5, SettingsManager.FPS_7_5, SettingsManager.FPS_10)
        val current = SettingsManager.getFps(this)
        var checked = values.indexOf(current)
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
        val current = SettingsManager.getDedup(this)
        var checked = values.indexOf(current)
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
        val current = SettingsManager.getEnhance(this)
        var checked = values.indexOf(current)
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
            "3200px 宽",
            "2400px 宽",
            "1500px 宽",
            "1200px 宽",
            "1080px 宽",
            "800px 宽（快）"
        )
        val values = intArrayOf(
            SettingsManager.SCALE_NATIVE,
            SettingsManager.SCALE_3200,
            SettingsManager.SCALE_2400,
            SettingsManager.SCALE_1500,
            SettingsManager.SCALE_1200,
            SettingsManager.SCALE_1080,
            SettingsManager.SCALE_800
        )
        val current = SettingsManager.getScale(this)
        var checked = values.indexOf(current)
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

    private fun toggleFloat() {
        val cur = SettingsManager.getFloat(this)
        SettingsManager.setFloat(this, !cur)
        Toast.makeText(this, if (!cur) "悬浮窗已开" else "悬浮窗已关", Toast.LENGTH_SHORT).show()
        refreshTexts()
    }

    private fun toggleVibrate() {
        val cur = SettingsManager.getVibrate(this)
        SettingsManager.setVibrate(this, !cur)
        Toast.makeText(this, if (!cur) "震动已开" else "震动已关", Toast.LENGTH_SHORT).show()
        refreshTexts()
    }
}
