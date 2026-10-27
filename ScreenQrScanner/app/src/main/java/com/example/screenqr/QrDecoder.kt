package com.example.screenqr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * ZXing 识别器：快速路径，正常失败了再试反色。
 */
object QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    fun decode(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 路径1：正常亮度（最快最常用）
        var result = tryDecode(w, h, pixels, false)
        if (result != null) return result

        // 路径2：反色（逆光/白码黑底）
        result = tryDecode(w, h, pixels, true)
        return result
    }

    private fun tryDecode(
        w: Int, h: Int, pixels: IntArray, invert: Boolean
    ): String? {
        return try {
            val src = RGBLuminanceSource(w, h, pixels)
            val source = if (invert) src.invert() else src
            val binary = BinaryBitmap(HybridBinarizer(source))
            reader.decode(binary).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
