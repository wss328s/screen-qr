package com.example.screenqr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusDot: View
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    companion object {
        private const val REQ_SCREEN_CAPTURE = 1001
        private const val REQ_OVERLAY = 1002
        private const val COLOR_RUNNING = 0xFF34D399.toInt()   // success
        private const val COLOR_IDLE = 0xFF64748B.toInt()      // text_tertiary
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusText = findViewById(R.id.statusText)
        statusSub = findViewById(R.id.statusSub)
        statusDot = findViewById(R.id.statusDot)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)

        startBtn.setOnClickListener { ensurePermissionsAndStart() }
        stopBtn.setOnClickListener {
            startService(Intent(this, QrScanService::class.java).setAction(QrScanService.ACTION_STOP))
            refreshStatus()
        }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refreshStatus()

        // 打开APP就自动请求权限
        if (!QrScanService.isRunning) {
            ensurePermissionsAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置/系统权限页返回时同步状态
        refreshStatus()
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
                    Toast.makeText(this, "已启动连续识屏", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                } else {
                    Toast.makeText(this, "未授权屏幕录制，无法识别", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshStatus() {
        val running = QrScanService.isRunning
        statusText.text = if (running) "正在识别中" else "未启动"
        statusSub.text = if (running) {
            "前台服务运行中 · 切到任意应用即可自动识别"
        } else {
            "点击启动，授予权限后开始识别"
        }
        startBtn.isEnabled = !running
        startBtn.alpha = if (running) 0.45f else 1f
        setDotColor(statusDot, if (running) COLOR_RUNNING else COLOR_IDLE)
    }

    /** 动态设置状态圆点颜色 */
    private fun setDotColor(view: View, color: Int) {
        val bg = view.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        }
    }
}
