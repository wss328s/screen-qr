package com.example.screenqr

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    companion object {
        private const val REQ_SCREEN_CAPTURE = 1001
        private const val REQ_OVERLAY = 1002
        private const val PREFS_NAME = "qr_prefs"
        private const val KEY_LICENSE = "license_verified"

        // 有效卡密列表
        private val validKeys = setOf(
            "DJI8888",
            "DJI6666",
            "DJI9999",
            "DJI1234",
            "DJI5678"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

        startBtn.setOnClickListener { ensurePermissionsAndStart() }
        stopBtn.setOnClickListener {
            startService(Intent(this, QrScanService::class.java).setAction(QrScanService.ACTION_STOP))
            refreshStatus()
        }

        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // 先验证卡密
        checkLicense()
    }

    private fun checkLicense() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val verified = prefs.getBoolean(KEY_LICENSE, false)

        if (verified) {
            // 已验证过，直接启动
            refreshStatus()
            if (!QrScanService.isRunning) {
                ensurePermissionsAndStart()
            }
        } else {
            // 弹窗输入卡密
            showLicenseDialog()
        }
    }

    private fun showLicenseDialog() {
        val input = EditText(this).apply {
            hint = "请输入卡密"
            setSingleLine(true)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("卡密验证")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("验证") { _, _ ->
                val key = input.text.toString().trim().uppercase()
                if (validKeys.contains(key)) {
                    // 验证通过，保存状态
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_LICENSE, true)
                        .apply()
                    Toast.makeText(this, "验证成功", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                    if (!QrScanService.isRunning) {
                        ensurePermissionsAndStart()
                    }
                } else {
                    Toast.makeText(this, "卡密错误，退出", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .show()
    }

    private fun ensurePermissionsAndStart() {
        // 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予\"显示在其他应用上层\"权限", Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        // 屏幕录制授权
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    ensurePermissionsAndStart()
                } else {
                    Toast.makeText(this, "未授予悬浮窗权限，无法运行", Toast.LENGTH_LONG).show()
                }
            }
            REQ_SCREEN_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val svc = Intent(this, QrScanService::class.java).apply {
                        action = QrScanService.ACTION_START
                        putExtra(QrScanService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(QrScanService.EXTRA_RESULT_DATA, data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svc)
                    } else {
                        startService(svc)
                    }
                    Toast.makeText(this, "已启动连续识屏，切到别的页面试试", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                } else {
                    Toast.makeText(this, "未授权屏幕录制，无法识别屏幕二维码", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshStatus() {
        val running = QrScanService.isRunning
        statusText.text = if (running) "状态：正在后台连续识别屏幕二维码" else "状态：未启动"
        startBtn.isEnabled = !running
    }
}
