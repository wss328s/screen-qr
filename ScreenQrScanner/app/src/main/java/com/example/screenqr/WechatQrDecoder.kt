package com.example.screenqr

import android.content.Context
import android.util.Log
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_wechat_qrcode.WeChatQRCode
import java.io.File

/**
 * 微信 QRCode 解码器（JavaCV 封装的 opencv_contrib wechat_qrcode 模块）。
 *
 * 微信方案核心：CNN(SSD+MobileNetV2)检测码位置 + QRSR 轻量超分增强小码，
 * 解码仍走 zxing-cpp 内核。
 *
 * 关键：JavaCV 加载入口必须是 [org.bytedeco.opencv.opencv_java]（聚合类），
 * 而不是单个模块类，否则依赖模块（core/dnn 等）的 JNI 不会按正确顺序加载，
 * 导致检测器初始化失败、永远识别不出。
 */
object WechatQrDecoder {

    private const val TAG = "WechatQrDecoder"

    private var detector: WeChatQRCode? = null
    private var loaded = false
    private var loadError: String? = null

    /** 供 UI 显示的最后一次状态（引擎加载/解码结果），便于真机排查 */
    @Volatile
    var lastStatus: String = "未初始化"
        private set

    /** 初始化：复制 assets 模型到 filesDir 并加载模型 */
    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        lastStatus = "加载中..."
        try {
            Loader.load(org.bytedeco.opencv.opencv_java::class.java)
            Log.i(TAG, "opencv_java loaded")
            lastStatus = "opencv已加载"

            val dir = File(ctx.filesDir, "wechat_qrcode").apply { mkdirs() }
            val detectProto = copyAsset(ctx, "wechat_qrcode/detect.prototxt", dir)
            val detectModel = copyAsset(ctx, "wechat_qrcode/detect.caffemodel", dir)
            val srProto = copyAsset(ctx, "wechat_qrcode/sr.prototxt", dir)
            val srModel = copyAsset(ctx, "wechat_qrcode/sr.caffemodel", dir)
            lastStatus = "模型已复制"

            detector = WeChatQRCode(
                detectProto.absolutePath, detectModel.absolutePath,
                srProto.absolutePath, srModel.absolutePath
            )
            loaded = true
            lastStatus = "引擎就绪"
            Log.i(TAG, "WeChatQRCode inited OK")
        } catch (e: Throwable) {
            loadError = e.toString()
            lastStatus = "加载失败: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "init failed: $loadError", e)
        }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dir: File): File {
        val f = File(dir, assetPath.substringAfterLast('/'))
        if (!f.exists() || f.length() == 0L) {
            ctx.assets.open(assetPath).use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return f
    }

    /**
     * 用微信引擎解码一帧灰度图。
     * @return 识别到的所有码内容（逐个返回，不拼接），未识别返回空列表
     */
    fun decode(gray: ByteArray, width: Int, height: Int): List<String> {
        val d = detector ?: return emptyList()
        if (width < 20 || height < 20) return emptyList()
        return try {
            val mat = Mat(height, width, opencv_core.CV_8UC1)
            mat.data().put(gray, 0, width * height)
            val results = d.detectAndDecode(mat)
            val out = ArrayList<String>()
            for (i in 0 until results.size()) {
                val s = results.get(i).string
                if (s.isNullOrBlank()) continue
                out.add(s)
            }
            mat.close()
            out
        } catch (e: Throwable) {
            Log.e(TAG, "decode error: ${e.message}")
            emptyList()
        }
    }

    /** 供调试：返回加载状态 */
    fun status(): String = when {
        loaded -> "loaded"
        loadError != null -> "error: $loadError"
        else -> "not-inited"
    }
}
