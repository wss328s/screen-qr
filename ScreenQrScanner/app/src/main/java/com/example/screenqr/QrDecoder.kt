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
    /**
     * 第一级解码：用于「基准宽度 × 2 放大」的图传画面。
     *
     * 实测结论（基于两段真实无人机图传素材，326/524 帧）：
     * - 二值化用 [BarcodeReader.Binarizer.LOCAL_AVERAGE]（局部均值）比全局直方图
     *   命中率高约 1.2 个百分点，且在该级就能解出约 43% 的可解帧；
     * - tryRotate / tryInvert 必须开启（码倾斜、逆光反色都很常见）；
     * - tryDownscale 必须关闭（我们自己已经放大，再降采样会破坏小码结构）。
     */
    fun decodeFast(
        gray: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): DecodeResult? {
        val options = BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE),
            tryHarder = false,
            tryRotate = true,
            tryInvert = true,
            tryDownscale = false,
            tryDenoise = false,
            binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE,
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
            // 图传场景：码倾斜、逆光反色非常常见，旋转/反色始终开启
            tryRotate = true,
            tryInvert = true,
            // 降采样会破坏已经放大过的小码结构，图传场景关闭；
            // 仅「增强」档额外加去噪（对付图传压缩噪点）
            tryDownscale = false,
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
