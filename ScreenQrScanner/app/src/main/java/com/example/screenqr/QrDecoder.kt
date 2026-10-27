package com.example.screenqr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * 纯 ZXing 离线识别器。加强模糊/逆光/畸变识别：多路径重试。
 */
object QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ASSUME_GS1 to false
            )
        )
    }

    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 路径1：正常亮度 + HybridBinarizer（最常用）
        var result = tryDecode(w, h, pixels, false, false)
        if (result != null) return result

        // 路径2：反色（逆光/白码黑底）
        result = tryDecode(w, h, pixels, true, false)
        if (result != null) return result

        // 路径3：正常亮度 + GlobalHistogramBinarizer（适合模糊低对比）
        result = tryDecode(w, h, pixels, false, true)
        if (result != null) return result

        // 路径4：反色 + GlobalHistogramBinarizer
        result = tryDecode(w, h, pixels, true, true)
        return result
    }

    private fun tryDecode(
        w: Int, h: Int, pixels: IntArray,
        invert: Boolean, useGlobal: Boolean
    ): String? {
        return try {
            val src = RGBLuminanceSource(w, h, pixels)
            val source = if (invert) src.invert() else src
            val binarizer = if (useGlobal) GlobalHistogramBinarizer(source) else HybridBinarizer(source)
            val binary = BinaryBitmap(binarizer)
            reader.decode(binary).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
