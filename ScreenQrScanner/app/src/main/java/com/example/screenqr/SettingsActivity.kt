package com.example.screenqr

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var fpsRow: LinearLayout
    private lateinit var dedupRow: LinearLayout
    private lateinit var enhanceRow: LinearLayout
    private lateinit var scaleRow: LinearLayout
    private lateinit var floatRow: LinearLayout
    private lateinit var vibrateRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "设置"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }

        // 标题
        val titleView = TextView(this).apply {
            text = "扫码设置"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(titleView)

        // 识别频率
        fpsRow = createRow("识别频率", "1 / 2 / 5 / 7.5 / 10 帧每秒")
        fpsRow.setOnClickListener { showFpsDialog() }
        layout.addView(fpsRow)

        // 去重窗口
        dedupRow = createRow("去重窗口", "3 / 5 / 10 秒")
        dedupRow.setOnClickListener { showDedupDialog() }
        layout.addView(dedupRow)

        // 识别增强
        enhanceRow = createRow("识别增强", "关闭 / 标准 / 增强")
        enhanceRow.setOnClickListener { showEnhanceDialog() }
        layout.addView(enhanceRow)

        // 分析精度
        scaleRow = createRow("分析精度", "原生 / 3200 / 2400 / 1500 / 1200 / 1080 / 800 px")
        scaleRow.setOnClickListener { showScaleDialog() }
        layout.addView(scaleRow)

        // 悬浮窗
        floatRow = createRow("悬浮窗", "开 / 关")
        floatRow.setOnClickListener { toggleFloat() }
        layout.addView(floatRow)

        // 震动
        vibrateRow = createRow("震动反馈", "开 / 关")
        vibrateRow.setOnClickListener { toggleVibrate() }
        layout.addView(vibrateRow)

        // 音效说明
        val soundHint = TextView(this).apply {
            text = "提示：扫到二维码时自动播放音效 + 震动"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        layout.addView(soundHint)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)

        refreshTexts()
    }

    private fun createRow(title: String, desc: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#16213e"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        row.addView(titleView)

        val descView = TextView(this).apply {
            id = View.generateViewId()
            text = desc
            textSize = 14f
            setTextColor(Color.parseColor("#00d9ff"))
            setPadding(0, 8, 0, 0)
        }
        row.addView(descView)

        return row
    }

    private fun refreshTexts() {
        val fps = SettingsManager.getFps(this)
        val fpsDesc = when (fps) {
            SettingsManager.FPS_1 -> "1 帧/秒（1000ms）"
            SettingsManager.FPS_2 -> "2 帧/秒（500ms）"
            SettingsManager.FPS_5 -> "5 帧/秒（200ms）"
            SettingsManager.FPS_7_5 -> "7.5 帧/秒（133ms）"
            SettingsManager.FPS_10 -> "10 帧/秒（100ms）"
            else -> "$fps 帧/秒"
        }
        (fpsRow.getChildAt(1) as TextView).text = fpsDesc

        val dedup = SettingsManager.getDedup(this)
        (dedupRow.getChildAt(1) as TextView).text = "$dedup 秒（同一内容$dedup秒内不重复）"

        val enhance = SettingsManager.getEnhance(this)
        val enhanceDesc = when (enhance) {
            SettingsManager.ENHANCE_OFF -> "关闭（失败时不增强）"
            SettingsManager.ENHANCE_STD -> "标准（对比度增强）"
            SettingsManager.ENHANCE_STRONG -> "增强（锐化+对比度）"
            else -> "未知"
        }
        (enhanceRow.getChildAt(1) as TextView).text = enhanceDesc

        val scale = SettingsManager.getScale(this)
        val scaleDesc = when (scale) {
            SettingsManager.SCALE_NATIVE -> "原生分辨率（不缩放，小码必看）"
            else -> "${scale}px 宽"
        }
        (scaleRow.getChildAt(1) as TextView).text = scaleDesc

        (floatRow.getChildAt(1) as TextView).text = if (SettingsManager.getFloat(this)) "已开启" else "已关闭"
        (vibrateRow.getChildAt(1) as TextView).text = if (SettingsManager.getVibrate(this)) "已开启" else "已关闭"
    }

    private fun showFpsDialog() {
        val items = arrayOf(
            "1 帧/秒（1000ms）",
            "2 帧/秒（500ms）",
            "5 帧/秒（200ms）",
            "7.5 帧/秒（133ms）",
            "10 帧/秒（100ms）"
        )
        val values = intArrayOf(
            SettingsManager.FPS_1,
            SettingsManager.FPS_2,
            SettingsManager.FPS_5,
            SettingsManager.FPS_7_5,
            SettingsManager.FPS_10
        )
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
