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
 * 前台服务：持续截屏 -> ZXing 识别 -> 悬浮窗弹出结果。
 */
class QrScanService : Service() {

    companion object {
        private const val TAG = "QrScanService"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        private const val CHANNEL_ID = "screen_qr_channel"
        private const val NOTI_ID = 1001

        // 全图增强档的最大宽度（保护上限，QR识别不需要更高；小码可到设置里手动开 3200）
        private const val MAX_DECODE_WIDTH = 1440
        // 快速档目标宽度：小图先跑，像素少→快；清晰码在此命中
        private const val FAST_DECODE_WIDTH = 720

        // 突发期（画面刚变化，可能有新码）的扫描间隔：15fps 已足够灵敏，30fps 白烧CPU
        private const val BURST_INTERVAL_MS = 66L
        // 重档（全图增强）最小间隔：静止画面无需每帧都跑重型解码
        private const val HEAVY_INTERVAL_MS = 400L
        // 突发期内重档的最小间隔：比静止期更密，但也要限流避免1.5秒内狂跑几十次
        private const val HEAVY_BURST_INTERVAL_MS = 150L

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

    // 后台线程：所有截屏+识别重计算都在这里，避免阻塞主线程ANR
    private val workerThread = HandlerThread("QrScanWorker").apply { start() }
    private val scanHandler = Handler(workerThread.looper)

    // 运动触发突发：画面变化后，在该 elapsedRealtime 时间戳前以最快速度扫描（降低新码出现时的延迟）
    private var burstUntilElapsed = 0L
    // 上一帧的灰度抽样（用于廉价运动检测）
    private var lastSample: ByteArray? = null
    // 上次实际提取/解码的时间（速率门，-1=尚未派发）
    private var lastDispatchAt = -1L
    // 上次执行重档（全图增强）解码的时间（重档独立节流，-1=尚未执行）
    private var lastHeavyAt = -1L

    // 活码网络请求线程池（独立于扫描线程，联网等待不阻塞扫码）
    private val liveExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    // 正在联网解析的活码URL（避免重复请求）
    private val pendingLive = HashSet<String>()
    // 活码 URL 最近一次触发解析的时间：冷却期内不重复解析，避免屏幕活码常驻时反复刷新
    private val liveLastResolvedAt = HashMap<String, Long>()
    // 同一活码 URL 的解析冷却时长（避免屏上活码常驻时反复 loading；重置后 2 秒内同码不重复解析）
    private val LIVE_COOLDOWN_MS = 2000L

    private var screenW = 0
    private var screenH = 0
    private var density = 0

    // 诊断计数（后台线程写、主线程读，用 @Volatile 保证可见性）
    @Volatile private var frameCount = 0
    @Volatile private var qrCount = 0
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
        // Android 14+ 需要指定服务类型；低于 14 用兼容方式进入前台
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, buildNotification("启动中...", "正在请求截屏权限"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, buildNotification("启动中...", "正在请求截屏权限"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 已在运行则忽略重复启动（防止重复注册 MediaProjection 回调/泄漏）
                if (isRunning) return START_STICKY
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
        if (mediaProjection == null) {
            updateStatusNotification("错误", "无法创建截屏投影，请重新授权")
            stopSelf()
            return
        }
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 系统主动停止截屏：清理资源并结束服务，避免残留半启动状态
                mainHandler.post {
                    updateStatusNotification("已停止", "截屏已被系统或用户停止")
                    stopSelf()
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
                cleanupCapture()
                stopSelf()
                return
            }
        } catch (e: Exception) {
            updateStatusNotification("错误", "截屏初始化异常: ${e.message}")
            // 初始化失败时清理已部分创建的资源，避免泄漏
            cleanupCapture()
            stopSelf()
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

    /** 处理一帧：运动采样 -> 速率门 -> 源帧拷贝 -> 小图快速档 -> (未中)全图重档 */
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

            // 1) 廉价运动采样（256点），画面变化 -> 突发 1.5 秒
            if (sampleMotion(srcBuffer, srcRowStride, pixelStride, imgW, imgH)) {
                burstUntilElapsed = now + 1500L
            }

            // 2) 扫描间隔：突发期最快 66ms(15fps)，否则按设定周期
            val interval = if (now < burstUntilElapsed) minOf(scanIntervalMs, BURST_INTERVAL_MS) else scanIntervalMs

            // 3) 速率门：未到间隔则跳过，避免密集提取/解码
            if (!shouldDispatch(now, interval)) return

            // 4) 源帧一次性拷贝（两级解码复用，不重复拷全屏）
            FrameExtractor.copySource(srcBuffer)
            val bounds = SettingsManager.getRegionBounds(this)

            // 5) 第一级：小图 + 最快配置（清晰正向码在此命中，耗时最低）
            val smallMax = if (scaleWidth > 0) minOf(scaleWidth, FAST_DECODE_WIDTH) else FAST_DECODE_WIDTH
            val small = FrameExtractor.extract(
                srcRowStride, pixelStride, imgW, imgH, bounds, smallMax
            )
            if (small != null && !isFrameFlat(small.gray, small.width, small.height, small.rowStride)) {
                small.gray.rewind()
                val fast = QrDecoder.decodeFast(small.gray, small.rowStride, small.width, small.height)
                if (fast != null) {
                    processResult(fast)
                    return
                }
            }

            // 6) 第二级：重档独立节流，避免「无码画面也每帧跑全图重型解码」拖慢整体
            //    - 突发期（画面刚变化，可能新码）：每 150ms 跑一次重档，保证灵敏
            //    - 静止期：增强档每 400ms 跑一次重档；关闭增强则只在突发期跑
            val heavyEnabled = now < burstUntilElapsed || enhanceMode != SettingsManager.ENHANCE_OFF
            val heavyInterval = if (now < burstUntilElapsed) HEAVY_BURST_INTERVAL_MS else HEAVY_INTERVAL_MS
            val doHeavy = heavyEnabled && (lastHeavyAt < 0 || now - lastHeavyAt >= heavyInterval)
            if (doHeavy) {
                lastHeavyAt = now
                val largeMax = if (scaleWidth > 0) scaleWidth else MAX_DECODE_WIDTH
                val large = FrameExtractor.extract(
                    srcRowStride, pixelStride, imgW, imgH, bounds, largeMax
                )
                if (large != null && !isFrameFlat(large.gray, large.width, large.height, large.rowStride)) {
                    large.gray.rewind()
                    val heavy = QrDecoder.decodeHeavy(
                        large.gray, large.rowStride, large.width, large.height, enhanceMode
                    )
                    if (heavy != null) {
                        processResult(heavy)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // 单帧处理异常不应中断服务；记录到日志便于排查
            android.util.Log.w(TAG, "handleFrame error: ${e.message}")
        }
    }

    /** 处理一个解码结果：按模式记录并刷新悬浮窗/反馈（含活码联网解析） */
    private fun processResult(result: QrDecoder.DecodeResult) {
        if (workMode == SettingsManager.MODE_GAME) {
            // 比赛导航模式
            val raw = result.text
            if (SettingsManager.getLiveCode(this) && LiveCodeResolver.isLiveCode(raw)) {
                // 活码：联网解析真实内容，再从真实内容提取数字（短链本身没有有效数字）
                val url = raw.trim()
                // 冷却：屏幕上的活码常驻时，同一 URL 3 秒内只解析一次，避免反复 loading/刷新
                if (!shouldResolveLive(url)) return
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
                // 冷却：屏幕上的活码常驻时，同一 URL 3 秒内只解析一次，避免反复 loading/刷新
                if (!shouldResolveLive(url)) return
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
     * 活码冷却判断：同一活码 URL 在 [LIVE_COOLDOWN_MS] 内只允许触发一次解析。
     * 屏幕上活码图案常驻时，重档每 150ms 就会命中一次；若每次都解析，
     * 会反复 beginLive/endLive 并刷新悬浮窗，表现为「反复横跳」。
     */
    private fun shouldResolveLive(url: String): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(liveLastResolvedAt) {
            val last = liveLastResolvedAt[url]
            if (last != null && now - last < LIVE_COOLDOWN_MS) {
                false
            } else {
                // 控制缓存规模，避免长期运行无限增长
                if (liveLastResolvedAt.size > 64) liveLastResolvedAt.clear()
                liveLastResolvedAt[url] = now
                true
            }
        }
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
            .setSmallIcon(R.mipmap.ic_launcher)
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
        cleanupCapture()
        FloatingResultManager.hideAll(this)
        try { liveExecutor.shutdownNow() } catch (_: Exception) {}
        workerThread.quitSafely()
        super.onDestroy()
    }

    /** 统一释放截屏相关资源（幂等，可重复调用） */
    private fun cleanupCapture() {
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
