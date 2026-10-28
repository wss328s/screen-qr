package com.example.screenqr

import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var fpsValue: TextView
    private lateinit var dedupValue: TextView
    private lateinit var enhanceValue: TextView
    private lateinit var scaleValue: TextView
    private lateinit var floatValue: TextView
    private lateinit var vibrateValue: TextView
    private lateinit var floatSwitch: MaterialSwitch
    private lateinit var vibrateSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        fpsValue = findViewById(R.id.fpsValue)
        dedupValue = findViewById(R.id.dedupValue)
        enhanceValue = findViewById(R.id.enhanceValue)
        scaleValue = findViewById(R.id.scaleValue)
        floatValue = findViewById(R.id.floatValue)
        vibrateValue = findViewById(R.id.vibrateValue)
        floatSwitch = findViewById(R.id.floatSwitch)
        vibrateSwitch = findViewById(R.id.vibrateSwitch)

        findViewById<MaterialCardView>(R.id.fpsCard).setOnClickListener { showFpsDialog() }
        findViewById<MaterialCardView>(R.id.dedupCard).setOnClickListener { showDedupDialog() }
        findViewById<MaterialCardView>(R.id.enhanceCard).setOnClickListener { showEnhanceDialog() }
        findViewById<MaterialCardView>(R.id.scaleCard).setOnClickListener { showScaleDialog() }

        floatSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setFloat(this, checked)
            floatValue.text = if (checked) "已开启" else "已关闭"
        }

        vibrateSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setVibrate(this, checked)
            vibrateValue.text = if (checked) "已开启" else "已关闭"
        }

        refreshTexts()
    }

    private fun refreshTexts() {
        val fps = SettingsManager.getFps(this)
        fpsValue.text = when (fps) {
            SettingsManager.FPS_1 -> "1 帧/秒（1000ms）"
            SettingsManager.FPS_2 -> "2 帧/秒（500ms）"
            SettingsManager.FPS_5 -> "5 帧/秒（200ms）"
            SettingsManager.FPS_7_5 -> "7.5 帧/秒（133ms）"
            SettingsManager.FPS_10 -> "10 帧/秒（100ms）"
            else -> "$fps 帧/秒"
        }

        val dedup = SettingsManager.getDedup(this)
        dedupValue.text = "$dedup 秒"

        val enhance = SettingsManager.getEnhance(this)
        enhanceValue.text = when (enhance) {
            SettingsManager.ENHANCE_OFF -> "关闭"
            SettingsManager.ENHANCE_STD -> "标准（对比度增强）"
            SettingsManager.ENHANCE_STRONG -> "增强（锐化+对比度）"
            else -> "未知"
        }

        val scale = SettingsManager.getScale(this)
        scaleValue.text = when (scale) {
            SettingsManager.SCALE_NATIVE -> "原生分辨率（不缩放）"
            else -> "${scale}px 宽"
        }

        val floatOn = SettingsManager.getFloat(this)
        floatSwitch.isChecked = floatOn
        floatValue.text = if (floatOn) "已开启" else "已关闭"

        val vibrateOn = SettingsManager.getVibrate(this)
        vibrateSwitch.isChecked = vibrateOn
        vibrateValue.text = if (vibrateOn) "已开启" else "已关闭"
    }

    private fun showFpsDialog() {
        val items = arrayOf(
            "1 帧/秒（1000ms）", "2 帧/秒（500ms）", "5 帧/秒（200ms）",
            "7.5 帧/秒（133ms）", "10 帧/秒（100ms）"
        )
        val values = intArrayOf(
            SettingsManager.FPS_1, SettingsManager.FPS_2, SettingsManager.FPS_5,
            SettingsManager.FPS_7_5, SettingsManager.FPS_10
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
}
