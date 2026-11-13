package com.example.screenqr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * 开机自启中转页（无界面）。
 *
 * 开机广播拉起它之后，它只做一件事：把系统的「录屏/投屏授权」对话框弹出来。
 * 用户点一次「立即开始」，就直接进入悬浮窗识别，全程看不到 App 主界面。
 *
 * 说明：
 * 1. 主题是半透明的（AndroidManifest 里指定），不调用 setContentView，所以屏幕上
 *    除了系统授权框之外什么都不显示，也不会进入最近任务。
 * 2. MediaProjection 的授权令牌不允许存盘跨重启复用，这一次系统确认无法省略；
 *    这里省掉的只是"先打开 App 界面再弹框"那一步。
 * 3. 继承 android.app.Activity 而不是 AppCompatActivity，是为了能直接用
 *    Theme.Translucent.NoTitleBar 这个系统主题（AppCompat 要求 AppCompat 主题）。
 */
class BootStartActivity : Activity() {

    companion object {
        private const val REQ_SCREEN_CAPTURE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已经在识别了，不用重复授权
        if (QrScanService.isRunning) {
            finish()
            return
        }

        // 悬浮窗权限是运行前提，同时也是 Android 10+ 允许后台启动 Activity 的豁免项。
        // 没有这个权限，开机后弹不出授权框，安静退出（用户可下拉通知栏进设置补授权）。
        if (!Settings.canDrawOverlays(this)) {
            finish()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm == null) {
            finish()
            return
        }

        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
        } catch (e: Exception) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            val svc = Intent(this, QrScanService::class.java).apply {
                action = QrScanService.ACTION_START
                putExtra(QrScanService.EXTRA_RESULT_CODE, resultCode)
                putExtra(QrScanService.EXTRA_RESULT_DATA, data)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
                else startService(svc)
            } catch (e: Exception) {
                // 启动失败时不弹任何界面：悬浮窗不会出现，用户可下拉通知栏进设置重启
            }
        }
        // 无论授权成功、取消还是失败，都立刻退出，不留残影
        finish()
    }
}
