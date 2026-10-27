package com.example.screenqr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 强化识别：旋转重试 + 对比度增强 + 反色，应对畸形/反光/歪斜。
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

        // 路径1：正常
        var result = tryDecode(w, h, pixels, false)
        if (result != null) return result

        // 路径2：反色（逆光/白码黑底）
        result = tryDecode(w, h, pixels, true)
        if (result != null) return result

        // 路径3：旋转90度
        result = tryDecodeRotated(w, h, pixels, 90)
        if (result != null) return result

        // 路径4：旋转180度
        result = tryDecodeRotated(w, h, pixels, 180)
        if (result != null) return result

        // 路径5：增强对比度后再试（反光/低对比）
        result = tryDecodeEnhanced(w, h, pixels)
        return result
    }

    private fun tryDecode(w: Int, h: Int, pixels: IntArray, invert: Boolean): String? {
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

    private fun tryDecodeRotated(w: Int, h: Int, pixels: IntArray, degrees: Int): String? {
        return try {
            val rotated = rotatePixels(pixels, w, h, degrees)
            val src = RGBLuminanceSource(rotated.width, rotated.height, rotated.pixels)
            val binary = BinaryBitmap(HybridBinarizer(src))
            reader.decode(binary).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun tryDecodeEnhanced(w: Int, h: Int, pixels: IntArray): String? {
        return try {
            // 对比度增强：亮的更亮，暗的更暗
            val enhanced = IntArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val nr = (r * 1.5f).toInt().coerceIn(0, 255)
                val ng = (g * 1.5f).toInt().coerceIn(0, 255)
                val nb = (b * 1.5f).toInt().coerceIn(0, 255)
                enhanced[i] = Color.rgb(nr, ng, nb)
            }
            val src = RGBLuminanceSource(w, h, enhanced)
            val binary = BinaryBitmap(HybridBinarizer(src))
            reader.decode(binary).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotatePixels(pixels: IntArray, w: Int, h: Int, degrees: Int): RotatedData {
        return when (degrees) {
            90 -> {
                val newPixels = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        newPixels[x * h + (h - 1 - y)] = pixels[y * w + x]
                    }
                }
                RotatedData(h, w, newPixels)
            }
            180 -> {
                val newPixels = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        newPixels[(h - 1 - y) * w + (w - 1 - x)] = pixels[y * w + x]
                    }
                }
                RotatedData(w, h, newPixels)
            }
            else -> RotatedData(w, h, pixels)
        }
    }

    private data class RotatedData(val width: Int, val height: Int, val pixels: IntArray)
}
