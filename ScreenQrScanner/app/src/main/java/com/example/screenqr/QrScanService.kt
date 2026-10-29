package com.example.screenqr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 前台服务：持续截屏 -> 灰度提取 -> native 识别 -> 悬浮窗弹出结果。
 *
 * 管线（线程解耦）：
 * - QrScanWorker：帧回调，做廉价运动采样、速率门、ROI 灰度提取、纯色/黑屏门控，
 *   只把"最新一帧"发布给解码线程（采集永不被解码阻塞）；
 * - QrDecoder：单线程 native 解码，只处理最新帧，过期/积压帧丢弃，结果回主线程更新UI；
 * - 活码联网：独立线程池，等待网络不阻塞扫码。
 */
class QrScanService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        private const val CHANNEL_ID = "screen_qr_channel"
        private const val NOTI_ID = 1001

        // 识别解码的最大宽度（保护上限，QR识别不需要更高）
        private const val MAX_DECODE_WIDTH = 1920
        // 待解码帧的最大年龄：超过则视为积压过期，丢弃（保证低延迟）
        private const val MAX_FRAME_AGE_MS = 500L

        @Volatile
        var isRunning = false
            private set
    }

    // 从设置读取参数
    private val workMode: Int get() = SettingsManager.getMode(this)
    private val scanIntervalMs: Long get() = SettingsManager.fpsToIntervalMs(SettingsManager.getFps(this))
    private val dedupTtlMs: Long get() = SettingsManager.getDedup(this).toLong() * 1000
    private val scaleWidth: Int get() = SettingsManager.getScale(this)
    private val enhanceMode: Int get() = SettingsManager.getEnhance(this)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 主线程handler：用于更新悬浮窗、通知
    private val mainHandler = Handler(Looper.getMainLooper())

    // 采集线程：运动采样 + 灰度提取（轻量，不做native解码）
    private val workerThread = HandlerThread("QrScanWorker").apply { start() }
    private val scanHandler = Handler(workerThread.looper)

    // 解码线程：native 重计算（与采集解耦，解码不阻塞运动检测）
    private val decoderThread = HandlerThread("QrDecoder").apply { start() }
    private val decoderHandler = Handler(decoderThread.looper)

    // 最新待解码帧（单槽，只保留最新）+ 单飞标志
    private val frameLock = Any()
    private var latestFrame: FrameExtractor.GrayFrame? = null
    private val decodeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    // 活码网络请求线程池（独立于扫描线程，联网等待不阻塞扫码）
    private val liveExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    // 正在联网解析的活码URL（避免重复请求）
    private val pendingLive = HashSet<String>()

    // 运动触发突发：画面变化后，在该 elapsedRealtime 时间戳前以最快速度扫描（降低新码出现时的延迟）
    private var burstUntilElapsed = 0L
    // 上一帧的灰度抽样（用于廉价运动检测）
    private var lastSample: ByteArray? = null
    // 上次实际提取的时间（速率门，-1=尚未派发）
    private var lastDispatchAt = -1L

    private var screenW = 0
    private var screenH = 0
    private var density = 0

    // 诊断计数
    private var frameCount = 0
    private var qrCount = 0
    private var startTimestamp = 0L

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatusNotification()
            mainHandler.postDelayed(this, 2000)
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
                mainHandler.post {
                    updateStatusNotification()
                }
            }
        }, mainHandler)

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
            // 事件驱动：一有新帧立即在后台线程处理（静止画面不产生帧，零开销）
            imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                    if (image != null) {
                        try {
                            handleFrame(image)
                        } finally {
                            try { image.close() } catch (_: Exception) {}
                        }
                    }
                }, scanHandler)
            }
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

        // 重置，显示悬浮窗（根据模式）
        if (workMode == SettingsManager.MODE_GAME) {
            GameState.reset()
        } else {
            ScanHistory.reset()
        }
        FloatingResultManager.update(this)

        isRunning = true
        updateStatusNotification()
        // 预加载音效
        FloatingResultManager.preloadSound(this)
        // 帧处理由 ImageReader 事件驱动（后台线程）；状态通知在后台线程周期刷新
        mainHandler.postDelayed(statusUpdateRunnable, 1000)
    }

    /** 采集线程：廉价运动采样 -> 速率门 -> ROI灰度提取 -> 纯色拒绝 -> 发布最新帧（不解码） */
    private fun handleFrame(image: android.media.Image) {
        frameCount++
        try {
            val plane = image.planes[0]
            val srcBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val srcRowStride = plane.rowStride
            val imgW = image.width
            val imgH = image.height
            val now = android.os.SystemClock.elapsedRealtime()

            // 1) 廉价运动采样（256点，直接读原始缓冲），画面变化 -> 突发 1.5 秒
            if (sampleMotion(srcBuffer, srcRowStride, pixelStride, imgW, imgH)) {
                burstUntilElapsed = now + 1500L
            }

            // 2) 有效扫描间隔：突发期最快 33ms(30fps)，否则按设定周期
            val interval = if (now < burstUntilElapsed) minOf(scanIntervalMs, 33L) else scanIntervalMs

            // 3) 速率门：未到间隔则跳过（运动采样已做，开销极小），避免密集提取
            if (!shouldDispatch(now, interval)) return

            // 4) ROI 单遍 裁剪 + 降采样 + 转灰度（不建 Bitmap）
            val bounds = SettingsManager.getRegionBounds(this)
            val maxDim = if (scaleWidth > 0) scaleWidth else MAX_DECODE_WIDTH
            val frame = FrameExtractor.extract(
                srcBuffer, srcRowStride, pixelStride,
                imgW, imgH, bounds, maxDim, now
            ) ?: return

            // 5) 黑屏/纯色帧解码前拒绝（QR 码本身高对比，不会被误判）
            if (isFrameFlat(frame.gray, frame.width, frame.height, frame.rowStride)) {
                FrameExtractor.recycle(frame)
                return
            }

            // 6) 发布给解码线程（只保留最新帧，采集不被解码阻塞）
            publishFrame(frame)
        } catch (e: Exception) {
        }
    }

    /** 发布最新帧：覆盖待解码槽（旧帧回收），若解码空闲则启动解码循环 */
    private fun publishFrame(frame: FrameExtractor.GrayFrame) {
        synchronized(frameLock) {
            val old = latestFrame
            latestFrame = frame
            if (old != null) FrameExtractor.recycle(old)
        }
        if (decodeInFlight.compareAndSet(false, true)) {
            decoderHandler.post { decodeLoop() }
        }
    }

    /** 解码线程：循环取最新帧，过期丢弃，native 解码并处理结果，结束后回收 */
    private fun decodeLoop() {
        while (true) {
            val frame = synchronized(frameLock) {
                val f = latestFrame
                latestFrame = null
                f
            }
            if (frame == null) break
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - frame.capturedAt <= MAX_FRAME_AGE_MS) {
                processFrame(frame)
            }
            FrameExtractor.recycle(frame)
        }
        decodeInFlight.set(false)
        // 二次检查：防止"解码空闲检查"与"发布"之间的竞态漏帧
        val missed = synchronized(frameLock) { latestFrame != null }
        if (missed && decodeInFlight.compareAndSet(false, true)) {
            decoderHandler.post { decodeLoop() }
        }
    }

    /** native 解码 + 结果处理（比赛/纯扫码/活码） */
    private fun processFrame(frame: FrameExtractor.GrayFrame) {
        frame.gray.rewind()
        val result = QrDecoder.decode(
            frame.gray, frame.rowStride, frame.width, frame.height, enhanceMode
        )
        if (result == null) return

        if (workMode == SettingsManager.MODE_GAME) {
            // 比赛导航模式
            val raw = result.text
            if (SettingsManager.getLiveCode(this) && LiveCodeResolver.isLiveCode(raw)) {
                // 活码：联网解析真实内容，再从真实内容提取数字（短链本身没有有效数字）
                val url = raw.trim()
                val dup = synchronized(pendingLive) { pendingLive.contains(url) }
                if (!dup) {
                    synchronized(pendingLive) { pendingLive.add(url) }
                    GameState.beginLive()
                    mainHandler.post { FloatingResultManager.update(this) }
                    liveExecutor.execute {
                        val live = try { LiveCodeResolver.resolve(url) } catch (e: Exception) { null }
                        synchronized(pendingLive) { pendingLive.remove(url) }
                        GameState.endLive()
                        // 解析失败：短链域名含数字（qr61→61），兜底会误记，直接丢弃不记录
                        val content = live?.content?.trim()
                        if (content.isNullOrBlank()) {
                            mainHandler.post { FloatingResultManager.update(this) }
                            return@execute
                        }
                        val isNew = GameState.parseAndRecord(content)
                        if (isNew) qrCount++
                        mainHandler.post {
                            FloatingResultManager.update(this)
                            if (isNew) FloatingResultManager.notifyRead(this)
                        }
                    }
                }
            } else {
                // 普通码
                val isNew = GameState.parseAndRecord(raw)
                if (isNew) {
                    qrCount++
                    mainHandler.post {
                        FloatingResultManager.update(this)
                        FloatingResultManager.notifyRead(this)
                    }
                }
            }
        } else {
            // 纯扫码模式
            val raw = result.text
            if (SettingsManager.getLiveCode(this) && LiveCodeResolver.isLiveCode(raw)) {
                // 活码（网络二维码）：独立线程联网读取后台真实内容
                val url = raw.trim()
                val dup = synchronized(pendingLive) { pendingLive.contains(url) }
                if (!dup) {
                    synchronized(pendingLive) { pendingLive.add(url) }
                    ScanHistory.beginLive()
                    mainHandler.post { FloatingResultManager.update(this) }
                    liveExecutor.execute {
                        val live = try {
                            LiveCodeResolver.resolve(url)
                        } catch (e: Exception) { null }
                        synchronized(pendingLive) { pendingLive.remove(url) }
                        ScanHistory.endLive()
                        // 成功显示真实内容，失败兜底显示原始链接
                        val display = live?.content ?: url
                        val isNew = ScanHistory.add(display)
                        if (isNew) qrCount++
                        val shouldNotify = ScanHistory.shouldNotify(display, dedupTtlMs)
                        mainHandler.post {
                            FloatingResultManager.update(this)
                            if (shouldNotify) FloatingResultManager.notifyRead(this)
                        }
                    }
                }
            } else {
                // 普通码
                val isNew = ScanHistory.add(raw)
                val shouldNotify = ScanHistory.shouldNotify(raw, dedupTtlMs)

                if (isNew) {
                    qrCount++
                    mainHandler.post { FloatingResultManager.update(this) }
                }

                if (shouldNotify) {
                    mainHandler.post { FloatingResultManager.notifyRead(this) }
                }
            }
        }
    }

    /** 速率门：距上次派发不足 interval 则返回 false */
    private fun shouldDispatch(now: Long, interval: Long): Boolean {
        if (lastDispatchAt >= 0 && now - lastDispatchAt < interval) return false
        lastDispatchAt = now
        return true
    }

    /**
     * 廉价运动采样：从原始 RGBA 缓冲抽样 16x16（256点），转灰度后与上一帧对比；
     * 变化点 > 3% 视为画面有运动。在提取/解码之前完成，开销极小。
     */
    private fun sampleMotion(
        buffer: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int
    ): Boolean {
        val nx = 16; val ny = 16
        val cur = ByteArray(nx * ny)
        for (j in 0 until ny) {
            val y = (j * h / ny).coerceAtMost(h - 1)
            val rowBase = y * rowStride
            for (i in 0 until nx) {
                val x = (i * w / nx).coerceAtMost(w - 1)
                val idx = rowBase + x * pixelStride
                val r = buffer.get(idx).toInt() and 0xFF
                val g = buffer.get(idx + 1).toInt() and 0xFF
                val b = buffer.get(idx + 2).toInt() and 0xFF
                // 与 zxing-cpp 核心 RGBToLum 一致
                cur[j * nx + i] = ((306 * r + 601 * g + 117 * b + 0x200) shr 10).toByte()
            }
        }
        val prev = lastSample
        val changed = if (prev != null && prev.size == cur.size) {
            var diff = 0
            for (i in cur.indices) {
                if (kotlin.math.abs((cur[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)) > 12) diff++
            }
            diff.toFloat() / cur.size > 0.03f
        } else true
        lastSample = cur
        return changed
    }

    /** 纯色/黑屏判断：抽样统计灰度方差，方差极低（接近纯色）返回 true，跳过解码 */
    private fun isFrameFlat(gray: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int): Boolean {
        val step = maxOf(1, (w * h) / 4000)  // 抽样约 4000 点
        var n = 0; var sum = 0L; var sumSq = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val v = gray.get(y * rowStride + x).toInt() and 0xFF
                sum += v; sumSq += v.toLong() * v; n++
                x += step
            }
            y += step
        }
        if (n == 0) return true
        val mean = sum.toDouble() / n
        val variance = (sumSq.toDouble() / n) - mean * mean
        return variance < 25.0
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
        mainHandler.removeCallbacks(statusUpdateRunnable)
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        FloatingResultManager.hideAll(this)
        try { liveExecutor.shutdownNow() } catch (_: Exception) {}
        try {
            synchronized(frameLock) {
                latestFrame?.let { FrameExtractor.recycle(it) }
                latestFrame = null
            }
        } catch (_: Exception) {}
        workerThread.quitSafely()
        decoderThread.quitSafely()
        super.onDestroy()
    }
}
