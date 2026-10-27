package com.example.screenqr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer

/**
 * 快速识别器：关掉努力模式，用更快的二值化算法。
 */
object QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
            )
        )
    }

    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 快速路径：GlobalHistogramBinarizer比HybridBinarizer快很多
        var result = tryDecode(w, h, pixels, false)
        if (result != null) return result

        // 反色
        result = tryDecode(w, h, pixels, true)
        return result
    }

    private fun tryDecode(
        w: Int, h: Int, pixels: IntArray, invert: Boolean
    ): String? {
        return try {
            val src = RGBLuminanceSource(w, h, pixels)
            val source = if (invert) src.invert() else src
            val binary = BinaryBitmap(GlobalHistogramBinarizer(source))
            reader.decode(binary).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
