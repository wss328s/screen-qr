package com.example.screenqr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 动态识别：高识别率优先，飞着扫一下就中。
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

        // 正常识别（高识别率）
        var result = tryDecode(w, h, pixels, false)
        if (result != null) return result

        // 反色（逆光/白码黑底）
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
