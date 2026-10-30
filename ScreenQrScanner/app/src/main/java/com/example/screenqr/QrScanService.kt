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
 * 鍓嶅彴鏈嶅姟锛氭寔缁埅灞?-> ZXing 璇嗗埆 -> 鎮诞绐楀脊鍑虹粨鏋溿€?
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

        // 鍏ㄥ浘澧炲己妗ｇ殑鏈€澶у搴︼紙淇濇姢涓婇檺锛孮R璇嗗埆涓嶉渶瑕佹洿楂橈級
        private const val MAX_DECODE_WIDTH = 1920
        // 蹇€熸。鐩爣瀹藉害锛氬皬鍥惧厛璺戯紝鍍忕礌灏戔啋蹇紱娓呮櫚鐮佸湪姝ゅ懡涓?
        private const val FAST_DECODE_WIDTH = 720

        // ===== 鍥句紶鍦烘櫙涓撶敤瑙ｇ爜鍙傛暟锛堝熀浜?3 娈电湡瀹炴棤浜烘満鍥句紶绱犳潗瀹炴祴纭畾锛?====
        // 瀹炴祴缁撹锛氫簩缁寸爜鍦ㄧ敾闈笂浣嶇疆涓嶅浐瀹氾紙鍙兘璐村湪涓嬭竟缂橈級锛屼笖甯歌鍊炬枩/鍗锋洸/钃濆簳锛?
        // 鍥犳蹇呴』**鍏ㄥ睆**瑙ｇ爜鑰岄潪鍥哄畾 ROI锛涘悓鏃跺繀椤绘斁澶э紝鍚﹀垯灏忕爜瀹屽叏瑙ｄ笉鍑恒€?
        //
        // 涓夌骇鍊嶇巼瀹炴祴锛坴1/v2/v3 涓夋绱犳潗锛屽叡 1566 甯э級锛?
        //   3540px 鍛戒腑鏈€澶氾紙91%锛夈€?720px 娆′箣锛堟晳鏇村皬鐨勭爜锛夈€?360px 鍏滃ぇ鐮?
        private const val DECODE_WIDTH_MAIN = 3540
        private const val DECODE_WIDTH_SMALL = 4720
        private const val DECODE_WIDTH_LARGE = 2360

        /** 鍏ㄥ睆杈圭晫锛堝浘浼犵敾闈㈤噷鐮佺殑浣嶇疆涓嶅浐瀹氾紝蹇呴』鏁村睆鎵級 */
        private val FULL_BOUNDS = floatArrayOf(0f, 0f, 1f, 1f)

        @Volatile
        var isRunning = false
            private set
    }

    // 浠庤缃鍙栧弬鏁?
    private val workMode: Int get() = SettingsManager.getMode(this)
    private val scanIntervalMs: Long get() = SettingsManager.fpsToIntervalMs(SettingsManager.getFps(this))
    private val dedupTtlMs: Long get() = SettingsManager.getDedup(this).toLong() * 1000
    private val enhanceMode: Int get() = SettingsManager.getEnhance(this)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 涓荤嚎绋媓andler锛氱敤浜庢洿鏂版偓娴獥銆侀€氱煡
    private val mainHandler = Handler(Looper.getMainLooper())

    // 鍚庡彴绾跨▼锛氭墍鏈夋埅灞?璇嗗埆閲嶈绠楅兘鍦ㄨ繖閲岋紝閬垮厤闃诲涓荤嚎绋婣NR
    private val workerThread = HandlerThread("QrScanWorker").apply { start() }
    private val scanHandler = Handler(workerThread.looper)

    // 杩愬姩瑙﹀彂绐佸彂锛氱敾闈㈠彉鍖栧悗锛屽湪璇?elapsedRealtime 鏃堕棿鎴冲墠浠ユ渶蹇€熷害鎵弿锛堥檷浣庢柊鐮佸嚭鐜版椂鐨勫欢杩燂級
    private var burstUntilElapsed = 0L
    // 涓婁竴甯х殑鐏板害鎶芥牱锛堢敤浜庡粔浠疯繍鍔ㄦ娴嬶級
    private var lastSample: ByteArray? = null
    // 涓婃瀹為檯鎻愬彇/瑙ｇ爜鐨勬椂闂达紙閫熺巼闂紝-1=灏氭湭娲惧彂锛?
    private var lastDispatchAt = -1L
    // 涓婃瑙ｇ爜瀹為檯鑰楁椂锛堟绉掞級锛岀敤浜庤嚜閫傚簲闂撮殧锛岄伩鍏嶅抚绉帇
    private var lastDecodeCostMs = 0L

    // 娲荤爜缃戠粶璇锋眰绾跨▼姹狅紙鐙珛浜庢壂鎻忕嚎绋嬶紝鑱旂綉绛夊緟涓嶉樆濉炴壂鐮侊級
    private val liveExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    // 姝ｅ湪鑱旂綉瑙ｆ瀽鐨勬椿鐮乁RL锛堥伩鍏嶉噸澶嶈姹傦級
    private val pendingLive = HashSet<String>()
    // 娲荤爜 URL 鏈€杩戜竴娆¤Е鍙戣В鏋愮殑鏃堕棿锛氬喎鍗存湡鍐呬笉閲嶅瑙ｆ瀽锛岄伩鍏嶅睆骞曟椿鐮佸父椹绘椂鍙嶅鍒锋柊
    private val liveLastResolvedAt = HashMap<String, Long>()
    // 鍚屼竴娲荤爜 URL 鐨勮В鏋愬喎鍗存椂闀匡紙閬垮厤灞忎笂娲荤爜甯搁┗鏃跺弽澶?loading锛涢噸缃悗 2 绉掑唴鍚岀爜涓嶉噸澶嶈В鏋愶級
    private val LIVE_COOLDOWN_MS = 2000L

    private var screenW = 0
    private var screenH = 0
    private var density = 0

    // 璇婃柇璁℃暟锛堝悗鍙扮嚎绋嬪啓銆佷富绾跨▼璇伙紝鐢?@Volatile 淇濊瘉鍙鎬э級
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
        // Android 14+ 闇€瑕佹寚瀹氭湇鍔＄被鍨嬶紱浣庝簬 14 鐢ㄥ吋瀹规柟寮忚繘鍏ュ墠鍙?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, buildNotification("鍚姩涓?..", "姝ｅ湪璇锋眰鎴睆鏉冮檺"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, buildNotification("鍚姩涓?..", "姝ｅ湪璇锋眰鎴睆鏉冮檺"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 宸插湪杩愯鍒欏拷鐣ラ噸澶嶅惎鍔紙闃叉閲嶅娉ㄥ唽 MediaProjection 鍥炶皟/娉勬紡锛?
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
            updateStatusNotification("閿欒", "鏃犳硶鍒涘缓鎴睆鎶曞奖锛岃閲嶆柊鎺堟潈")
            stopSelf()
            return
        }
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 绯荤粺涓诲姩鍋滄鎴睆锛氭竻鐞嗚祫婧愬苟缁撴潫鏈嶅姟锛岄伩鍏嶆畫鐣欏崐鍚姩鐘舵€?
                mainHandler.post {
                    updateStatusNotification("宸插仠姝?, "鎴睆宸茶绯荤粺鎴栫敤鎴峰仠姝?)
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
            // 浜嬩欢椹卞姩锛氫竴鏈夋柊甯х珛鍗冲湪鍚庡彴绾跨▼澶勭悊锛堥潤姝㈢敾闈笉浜х敓甯э紝闆跺紑閿€锛?
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
                updateStatusNotification("閿欒", "VirtualDisplay 鍒涘缓澶辫触锛岀郴缁熶笉鍏佽鎴睆")
                cleanupCapture()
                stopSelf()
                return
            }
        } catch (e: Exception) {
            updateStatusNotification("閿欒", "鎴睆鍒濆鍖栧紓甯? ${e.message}")
            // 鍒濆鍖栧け璐ユ椂娓呯悊宸查儴鍒嗗垱寤虹殑璧勬簮锛岄伩鍏嶆硠婕?
            cleanupCapture()
            stopSelf()
            return
        }

        // 閲嶇疆锛屾樉绀烘偓娴獥锛堟牴鎹ā寮忥級
        if (workMode == SettingsManager.MODE_GAME) {
            GameState.reset()
        } else {
            ScanHistory.reset()
        }
        FloatingResultManager.update(this)

        isRunning = true
        updateStatusNotification()
        // 棰勫姞杞介煶鏁?
        FloatingResultManager.preloadSound(this)
        // 甯у鐞嗙敱 ImageReader 浜嬩欢椹卞姩锛堝悗鍙扮嚎绋嬶級锛涚姸鎬侀€氱煡鍦ㄥ悗鍙扮嚎绋嬪懆鏈熷埛鏂?
        mainHandler.postDelayed(statusUpdateRunnable, 1000)
    }

    /**
     * 澶勭悊涓€甯э細杩愬姩閲囨牱 -> 閫熺巼闂?-> 婧愬抚鎷疯礉 -> 澶氱骇鍊嶇巼绾ц仈瑙ｇ爜銆?
     *
     * 娉ㄦ剰锛氬浘浼犵敾闈㈡斁澶у埌 3540px 鍚庡崟甯цВ鐮佺害 30~40ms锛岃嫢鍥哄畾 33ms 闂撮殧浼氬鑷?
     * 甯хН鍘嬨€佸欢杩熻秺鎷栬秺澶с€傚洜姝よ繖閲岀敤銆岃嚜閫傚簲闂撮殧銆嶏細浠ュ疄娴嬭€楁椂涓哄噯锛?
     * 淇濊瘉瑙ｇ爜姘歌繙涓嶅爢绉紙瀹佸彲灏戣В鍑犲抚锛屼篃涓嶈寤惰繜绱Н锛夈€?
     */
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

            // 1) 寤変环杩愬姩閲囨牱锛?56鐐癸級锛岀敾闈㈠彉鍖?-> 绐佸彂 1.5 绉?
            if (sampleMotion(srcBuffer, srcRowStride, pixelStride, imgW, imgH)) {
                burstUntilElapsed = now + 1500L
            }

            // 2) 鎵弿闂撮殧锛氱獊鍙戞湡灏介噺蹇紝浣嗕笉寰楀皬浜庝笂娆″疄闄呰В鐮佽€楁椂锛堥槻绉帇锛?
            val userInterval = scanIntervalMs
            val burstInterval = minOf(userInterval, 33L)
            val adaptiveFloor = (lastDecodeCostMs * 1.1f).toLong().coerceAtLeast(33L)
            val interval = if (now < burstUntilElapsed) maxOf(burstInterval, adaptiveFloor)
                           else maxOf(userInterval, adaptiveFloor)

            // 3) 閫熺巼闂細鏈埌闂撮殧鍒欒烦杩囷紝閬垮厤瀵嗛泦鎻愬彇/瑙ｇ爜
            if (!shouldDispatch(now, interval)) return

            // 4) 婧愬抚涓€娆℃€ф嫹璐濓紙鍚勭骇瑙ｇ爜澶嶇敤锛屼笉閲嶅鎷峰叏灞忥級
            FrameExtractor.copySource(srcBuffer)
            val decodeStart = android.os.SystemClock.elapsedRealtime()

            // ===== 鍥句紶鍦烘櫙瑙ｇ爜绾ц仈锛堝弬鏁扮敱 3 娈电湡瀹炵礌鏉愬疄娴嬬‘瀹氾級=====
            // 瀹炴祴锛氫簩缁寸爜浣嶇疆涓嶅浐瀹氾紙鍙兘璐寸敾闈笅杈圭紭锛夛紝鍥哄畾 ROI 浼氭妸鐮佽鎺変竴鍗婏紱
            // 涓斿繀椤绘斁澶э紝鍚﹀垯杩滃灏忕爜瀹屽叏瑙ｄ笉鍑恒€傛晠姝ゅ鐢ㄥ叏灞?+ 澶氱骇鍊嶇巼銆?
            // 鍏ㄥ睆杈圭晫 [0,0,1,1]
            val full = FULL_BOUNDS

            // 绗?1 绾э細鍏ㄥ睆 脳 3540锛堝疄娴?91% 鐨勫懡涓兘鍦ㄨ繖绾э紝绾?30-40ms锛?
            val main = FrameExtractor.extractToWidth(
                srcRowStride, pixelStride, imgW, imgH, full, DECODE_WIDTH_MAIN, slot = 1
            )
            if (main != null && !isFrameFlat(main.gray, main.width, main.height, main.rowStride)) {
                main.gray.rewind()
                val hit = QrDecoder.decodeFast(
                    main.gray, main.rowStride, main.width, main.height
                )
                if (hit != null) {
                    processResult(hit)
                    return
                }
            }

            // 绗?2 绾э細鍏ㄥ睆 脳 4720锛堟洿灏忕殑鐮侊紱绐佸彂鏈熸垨澧炲己寮€鍚椂灏濊瘯锛屾帶鍒跺姛鑰楋級
            val doSecond = now < burstUntilElapsed || enhanceMode != SettingsManager.ENHANCE_OFF
            if (doSecond) {
                val small = FrameExtractor.extractToWidth(
                    srcRowStride, pixelStride, imgW, imgH, full, DECODE_WIDTH_SMALL, slot = 2
                )
                if (small != null) {
                    small.gray.rewind()
                    val hit2 = QrDecoder.decodeHeavy(
                        small.gray, small.rowStride, small.width, small.height, enhanceMode
                    )
                    if (hit2 != null) {
                        processResult(hit2)
                        return
                    }
                }

                // 绗?3 绾э細鍏ㄥ睆 脳 2360锛堜竴娆℃€у嚭鐜扮殑杈冨ぇ鐮侊級
                val large = FrameExtractor.extractToWidth(
                    srcRowStride, pixelStride, imgW, imgH, full, DECODE_WIDTH_LARGE, slot = 3
                )
                if (large != null) {
                    large.gray.rewind()
                    val hit3 = QrDecoder.decodeHeavy(
                        large.gray, large.rowStride, large.width, large.height, enhanceMode
                    )
                    if (hit3 != null) {
                        processResult(hit3)
                        return
                    }
                }
            }

            // 璁板綍鏈抚瑙ｇ爜鑰楁椂锛堟湭鍛戒腑璺緞锛夛紝渚涜嚜閫傚簲闂撮殧浣跨敤
            lastDecodeCostMs = android.os.SystemClock.elapsedRealtime() - decodeStart
        } catch (e: Exception) {
            // 鍗曞抚澶勭悊寮傚父涓嶅簲涓柇鏈嶅姟锛涜褰曞埌鏃ュ織渚夸簬鎺掓煡
            android.util.Log.w(TAG, "handleFrame error: ${e.message}")
        }
    }

    /** 澶勭悊涓€涓В鐮佺粨鏋滐細鎸夋ā寮忚褰曞苟鍒锋柊鎮诞绐?鍙嶉锛堝惈娲荤爜鑱旂綉瑙ｆ瀽锛?*/
    private fun processResult(result: QrDecoder.DecodeResult) {
        if (workMode == SettingsManager.MODE_GAME) {
            // 姣旇禌瀵艰埅妯″紡
            val raw = result.text
            if (SettingsManager.getLiveCode(this) && LiveCodeResolver.isLiveCode(raw)) {
                // 娲荤爜锛氳仈缃戣В鏋愮湡瀹炲唴瀹癸紝鍐嶄粠鐪熷疄鍐呭鎻愬彇鏁板瓧锛堢煭閾炬湰韬病鏈夋湁鏁堟暟瀛楋級
                val url = raw.trim()
                // 鍐峰嵈锛氬睆骞曚笂鐨勬椿鐮佸父椹绘椂锛屽悓涓€ URL 3 绉掑唴鍙В鏋愪竴娆★紝閬垮厤鍙嶅 loading/鍒锋柊
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
                        // 瑙ｆ瀽澶辫触锛氱煭閾惧煙鍚嶅惈鏁板瓧锛坬r61鈫?1锛夛紝鍏滃簳浼氳璁帮紝鐩存帴涓㈠純涓嶈褰?
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
                // 鏅€氱爜
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
            // 绾壂鐮佹ā寮?
            val raw = result.text
            if (SettingsManager.getLiveCode(this) && LiveCodeResolver.isLiveCode(raw)) {
                // 娲荤爜锛堢綉缁滀簩缁寸爜锛夛細鐙珛绾跨▼鑱旂綉璇诲彇鍚庡彴鐪熷疄鍐呭
                val url = raw.trim()
                // 鍐峰嵈锛氬睆骞曚笂鐨勬椿鐮佸父椹绘椂锛屽悓涓€ URL 3 绉掑唴鍙В鏋愪竴娆★紝閬垮厤鍙嶅 loading/鍒锋柊
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
                        // 鎴愬姛鏄剧ず鐪熷疄鍐呭锛屽け璐ュ厹搴曟樉绀哄師濮嬮摼鎺?
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
                // 鏅€氱爜
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

    /** 閫熺巼闂細璺濅笂娆℃淳鍙戜笉瓒?interval 鍒欒繑鍥?false */
    private fun shouldDispatch(now: Long, interval: Long): Boolean {
        if (lastDispatchAt >= 0 && now - lastDispatchAt < interval) return false
        lastDispatchAt = now
        return true
    }

    /**
     * 娲荤爜鍐峰嵈鍒ゆ柇锛氬悓涓€娲荤爜 URL 鍦?[LIVE_COOLDOWN_MS] 鍐呭彧鍏佽瑙﹀彂涓€娆¤В鏋愩€?
     * 灞忓箷涓婃椿鐮佸浘妗堝父椹绘椂锛岄噸妗ｆ瘡 150ms 灏变細鍛戒腑涓€娆★紱鑻ユ瘡娆￠兘瑙ｆ瀽锛?
     * 浼氬弽澶?beginLive/endLive 骞跺埛鏂版偓娴獥锛岃〃鐜颁负銆屽弽澶嶆í璺炽€嶃€?
     */
    private fun shouldResolveLive(url: String): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(liveLastResolvedAt) {
            val last = liveLastResolvedAt[url]
            if (last != null && now - last < LIVE_COOLDOWN_MS) {
                false
            } else {
                // 鎺у埗缂撳瓨瑙勬ā锛岄伩鍏嶉暱鏈熻繍琛屾棤闄愬闀?
                if (liveLastResolvedAt.size > 64) liveLastResolvedAt.clear()
                liveLastResolvedAt[url] = now
                true
            }
        }
    }

    /**
     * 寤変环杩愬姩閲囨牱锛氫粠鍘熷 RGBA 缂撳啿鎶芥牱 16x16锛?56鐐癸級锛岃浆鐏板害鍚庝笌涓婁竴甯у姣旓紱
     * 鍙樺寲鐐?> 3% 瑙嗕负鐢婚潰鏈夎繍鍔ㄣ€傚湪鎻愬彇/瑙ｇ爜涔嬪墠瀹屾垚锛屽紑閿€鏋佸皬銆?
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
                // 涓?zxing-cpp 鏍稿績 RGBToLum 涓€鑷?
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

    /** 绾壊/榛戝睆鍒ゆ柇锛氭娊鏍风粺璁＄伆搴︽柟宸紝鏂瑰樊鏋佷綆锛堟帴杩戠函鑹诧級杩斿洖 true锛岃烦杩囪В鐮?*/
    private fun isFrameFlat(gray: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int): Boolean {
        val step = maxOf(1, (w * h) / 4000)  // 鎶芥牱绾?4000 鐐?
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
        val finalTitle = title ?: "灞忓箷浜岀淮鐮佽瘑鍒腑"
        val finalText = text ?: if (frameCount == 0 && elapsed > 3) {
            "宸茶繍琛?{elapsed}绉掞紝鏈幏鍙栧埌鐢婚潰锛堢郴缁熷彲鑳界姝㈡埅灞忥級"
        } else {
            "宸叉埅${frameCount}甯э紝璇嗗埆${qrCount}涓爜锛?{elapsed}绉掞級"
        }
        nm.notify(NOTI_ID, buildNotification(finalTitle, finalText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "灞忓箷浜岀淮鐮佽瘑鍒?, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "杩炵画璇嗗埆灞忓箷涓婂嚭鐜扮殑浜岀淮鐮? }
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

    /** 缁熶竴閲婃斁鎴睆鐩稿叧璧勬簮锛堝箓绛夛紝鍙噸澶嶈皟鐢級 */
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
