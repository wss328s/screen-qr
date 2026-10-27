package com.example.screenqr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 前台服务：持续截屏 -> ZXing 识别 -> 悬浮窗弹出结果。
 */
class QrScanService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        private const val CHANNEL_ID = "screen_qr_channel"
        private const val NOTI_ID = 1001

        private const val SCAN_INTERVAL_MS = 200L
        private const val DEDUP_TTL_MS = 3000L
        private const val SCALE_WIDTH = 500

        @Volatile
        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recent = LinkedHashMap<String, Long>()

    private var screenW = 0
    private var screenH = 0
    private var density = 0

    // 诊断计数
    private var frameCount = 0
    private var qrCount = 0
    private var startTimestamp = 0L

    private val scanRunnable = object : Runnable {
        override fun run() {
            scanOnce()
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatusNotification()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTI_ID, buildNotification("启动中...", "正在请求截屏权限"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapturing(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startCapturing(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 系统主动停止截屏，不要直接杀服务，更新通知让用户看到
                handler.post {
                    updateStatusNotification()
                }
            }
        }, null)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        density = metrics.densityDpi

        startTimestamp = System.currentTimeMillis()
        frameCount = 0
        qrCount = 0

        try {
            imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenQrCapture",
                screenW, screenH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            if (virtualDisplay == null) {
                updateStatusNotification("错误", "VirtualDisplay 创建失败，系统不允许截屏")
                return
            }
        } catch (e: Exception) {
            updateStatusNotification("错误", "截屏初始化异常: ${e.message}")
            return
        }

        // 重置比赛状态，显示悬浮窗
        GameState.reset()
        FloatingResultManager.update(this)

        isRunning = true
        updateStatusNotification()
        handler.postDelayed(scanRunnable, 300)
        handler.postDelayed(statusUpdateRunnable, 1000)
    }

    private fun scanOnce() {
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        image ?: return

        frameCount++

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenW

            val fullW = screenW + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(fullW, screenH, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = if (fullW != screenW) {
                Bitmap.createBitmap(bitmap, 0, 0, screenW, screenH).also { bitmap.recycle() }
            } else bitmap

            // 只识别中心70%区域，二维码占比更大，识别更快
            val cropW = (screenW * 0.7f).toInt()
            val cropH = (screenH * 0.7f).toInt()
            val offsetX = (screenW - cropW) / 2
            val offsetY = (screenH - cropH) / 2
            val center = Bitmap.createBitmap(cropped, offsetX, offsetY, cropW, cropH)
            if (center != cropped) cropped.recycle()

            val scaleH = (SCALE_WIDTH * cropH / cropW.toFloat()).toInt()
            val scaled = if (cropW > SCALE_WIDTH) {
                Bitmap.createScaledBitmap(center, SCALE_WIDTH, scaleH, false).also {
                    if (it != center) center.recycle()
                }
            } else center

            val text = QrDecoder.decode(scaled)
            scaled.recycle()

            if (!text.isNullOrBlank()) {
                // 尝试解析为比赛码（A31格式）
                if (GameState.parseAndRecord(text)) {
                    qrCount++
                    FloatingResultManager.update(this)
                    FloatingResultManager.notifyRead(this)
                }
            }
        } catch (e: Exception) {
            // 单帧失败不影响下一轮
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun isDuplicate(content: String): Boolean {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value > DEDUP_TTL_MS }
        if (recent.containsKey(content)) return true
        recent[content] = now
        return false
    }

    private fun buildNotification(title: String, text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun updateStatusNotification(title: String? = null, text: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val elapsed = (System.currentTimeMillis() - startTimestamp) / 1000
        val finalTitle = title ?: "屏幕二维码识别中"
        val finalText = text ?: if (frameCount == 0 && elapsed > 3) {
            "已运行${elapsed}秒，未获取到画面（系统可能禁止截屏）"
        } else {
            "已截${frameCount}帧，识别${qrCount}个码（${elapsed}秒）"
        }
        nm.notify(NOTI_ID, buildNotification(finalTitle, finalText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "屏幕二维码识别", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "连续识别屏幕上出现的二维码" }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(statusUpdateRunnable)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        FloatingResultManager.hideAll(this)
        super.onDestroy()
    }
}
