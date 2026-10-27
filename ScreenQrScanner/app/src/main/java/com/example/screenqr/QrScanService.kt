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
 *
 * 关键调参：
 *  - SCAN_INTERVAL_MS：识别间隔。越小越跟手，但越费电。建议 500~800ms。
 *  - DEDUP_TTL_MS：同一内容多久内不重复弹。避免你盯着同一个二维码时疯狂弹窗。
 *  - SCALE_WIDTH：识别前把帧缩到多宽。二维码一般几十像素就够，缩小能大幅加速。
 */
class QrScanService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        private const val CHANNEL_ID = "screen_qr_channel"
        private const val NOTI_ID = 1001

        private const val SCAN_INTERVAL_MS = 250L
        private const val DEDUP_TTL_MS = 2000L
        private const val SCALE_WIDTH = 400

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

    private val scanRunnable = object : Runnable {
        override fun run() {
            scanOnce()
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val noti: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕二维码识别中")
            .setContentText("正在后台连续监听屏幕二维码")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(NOTI_ID, noti)
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
            override fun onStop() { stopSelf() }
        }, null)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        density = metrics.densityDpi

        // ImageReader 缓冲 2 帧，避免丢帧
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenQrCapture",
            screenW, screenH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        isRunning = true
        handler.postDelayed(scanRunnable, 300)
    }

    private fun scanOnce() {
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
        image ?: return

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenW

            val fullW = screenW + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(fullW, screenH, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪掉右边 padding，再缩放到 SCALE_WIDTH 加速识别
            val cropped = if (fullW != screenW) {
                Bitmap.createBitmap(bitmap, 0, 0, screenW, screenH).also { bitmap.recycle() }
            } else bitmap

            val scaleH = (SCALE_WIDTH * screenH / screenW.toFloat()).toInt()
            val scaled = if (screenW > SCALE_WIDTH) {
                Bitmap.createScaledBitmap(cropped, SCALE_WIDTH, scaleH, false).also {
                    if (it != cropped) cropped.recycle()
                }
            } else cropped

            val text = QrDecoder.decode(scaled)
            scaled.recycle()

            if (!text.isNullOrBlank() && !isDuplicate(text)) {
                FloatingResultManager.show(this, text)
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
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        FloatingResultManager.hideAll(this)
        super.onDestroy()
    }
}
