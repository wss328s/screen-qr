package com.example.screenqr

import zxingcpp.BarcodeReader
import java.nio.ByteBuffer

/**
 * 识别解码器：使用 zxing-cpp native 库（C++ 机器码），直接读取灰度帧。
 *
 * 采用两级级联（先快后全），由 QrScanService 调度：
 * - [decodeFast]：最快配置（全局直方图二值化、不 tryHarder/旋转/反色、找到一个即停），
 *   清晰、正向的二维码在此一次命中，耗时最低；
 * - [decodeHeavy]：仅在快速档未命中时调用，重配置（局部均值二值化 + tryHarder +
 *   旋转 + 反色，增强档再开降采样/去噪），覆盖小码/模糊/反光/倾斜/反色。
 *
 * 输入已是 FrameExtractor 产出的灰度（1 字节/像素），不经过 Bitmap，
 * native 也无需再做 RGBA->灰度转换（最快路径）。
 */
object QrDecoder {

    data class DecodeResult(
        val text: String,
        val points: FloatArray? = null
    )

    private val reader = BarcodeReader()

    /** 快速档：最低工作量，处理清晰正向二维码。 */
    fun decodeFast(
        gray: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): DecodeResult? {
        val options = BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE),
            tryHarder = false,
            tryRotate = false,
            tryInvert = false,
            tryDownscale = false,
            tryDenoise = false,
            // 全局直方图二值化：屏幕画面干净、对比度高，速度最快
            binarizer = BarcodeReader.Binarizer.GLOBAL_HISTOGRAM,
            // 找到一个即停（早期退出）
            maxNumberOfSymbols = 1,
            textMode = BarcodeReader.TextMode.PLAIN
        )
        reader.options = options
        return runDecode(gray, rowStride, width, height)
    }

    /** 重档：快速档未命中时调用，覆盖各种困难情况。 */
    fun decodeHeavy(
        gray: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        enhanceMode: Int = SettingsManager.ENHANCE_STD
    ): DecodeResult? {
        val options = BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE),
            tryHarder = enhanceMode != SettingsManager.ENHANCE_OFF,
            tryRotate = enhanceMode != SettingsManager.ENHANCE_OFF,
            tryInvert = enhanceMode != SettingsManager.ENHANCE_OFF,
            // 仅增强档开降采样尝试 + 去噪（小码/模糊/虚焦）
            tryDownscale = enhanceMode == SettingsManager.ENHANCE_STRONG,
            tryDenoise = enhanceMode == SettingsManager.ENHANCE_STRONG,
            // 局部均值二值化：强反光/阴影/光照不均更稳
            binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE,
            maxNumberOfSymbols = 1,
            textMode = BarcodeReader.TextMode.PLAIN
        )
        reader.options = options
        return runDecode(gray, rowStride, width, height)
    }

    private fun runDecode(
        gray: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): DecodeResult? {
        return try {
            // 灰度帧一次 native 读取
            val results = reader.readGray(gray, rowStride, width, height)
            val first = results.firstOrNull { !it.text.isNullOrBlank() }
            first?.let { r ->
                DecodeResult(
                    r.text!!,
                    r.position.let { p ->
                        floatArrayOf(
                            p.topLeft.x.toFloat(), p.topLeft.y.toFloat(),
                            p.topRight.x.toFloat(), p.topRight.y.toFloat(),
                            p.bottomRight.x.toFloat(), p.bottomRight.y.toFloat(),
                            p.bottomLeft.x.toFloat(), p.bottomLeft.y.toFloat()
                        )
                    }
                )
            }
        } catch (e: Throwable) {
            null
        }
    }
}
