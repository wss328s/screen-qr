package com.example.screenqr

import zxingcpp.BarcodeReader
import java.nio.ByteBuffer

/**
 * 识别解码器：使用 zxing-cpp native 库（C++ 机器码），直接读取灰度帧。
 *
 * 关键点：
 * - 输入已是 FrameExtractor 按 ROI 裁剪/降采样后的灰度（1 字节/像素），
 *   不再经过 Bitmap，native 也无需再做 RGBA->灰度转换（最快路径）；
 * - native 一次调用即可同时处理 多码、旋转、反色、降采样、去噪，无需 Java 层反复尝试；
 * - 针对 模糊（去噪/tryHarder）、反光/阴影（LOCAL_AVERAGE 局部二值化）、
 *   低对比、畸形/倾斜（tryRotate 任意角度）、白码黑底（tryInvert）；
 * - 输出纯文本（TextMode.PLAIN）。
 */
object QrDecoder {

    data class DecodeResult(
        val text: String,
        val points: FloatArray? = null
    )

    private val reader = BarcodeReader()

    fun decode(
        gray: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        enhanceMode: Int = SettingsManager.ENHANCE_STD
    ): DecodeResult? {
        // 根据增强档位设置 native 选项
        val options = BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE),
            // 关闭：只做最基础的正向识别（最快）
            tryHarder = enhanceMode != SettingsManager.ENHANCE_OFF,
            // 图传画面非正对：任意角度旋转；标准/增强都开
            tryRotate = enhanceMode != SettingsManager.ENHANCE_OFF,
            // 白码黑底/反色
            tryInvert = enhanceMode != SettingsManager.ENHANCE_OFF,
            // 增强档：降采样尝试 + 去噪（小码/模糊/虚焦）
            tryDownscale = enhanceMode == SettingsManager.ENHANCE_STRONG,
            tryDenoise = enhanceMode == SettingsManager.ENHANCE_STRONG,
            // 局部均值二值化，对强反光/阴影/光照不均更稳
            binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE,
            // 输出纯文本，不带 HRI 前缀
            textMode = BarcodeReader.TextMode.PLAIN
        )
        reader.options = options

        return try {
            // 灰度帧一次 native 读取，返回所有识别到的码
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
