package com.example.screenqr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer

/**
 * 强化识别：返回内容+位置坐标，用于画框。
 */
object QrDecoder {

    data class DecodeResult(
        val text: String,
        val points: FloatArray? = null  // 二维码角点坐标
    )

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    fun decode(bitmap: Bitmap, enhanceMode: Int = SettingsManager.ENHANCE_STD): DecodeResult? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. 正常识别（最常见）
        var result = tryDecode(w, h, pixels, false)
        if (result != null) return result

        // 2. 反色识别（白码黑底）
        result = tryDecode(w, h, pixels, true)
        if (result != null) return result

        // 3. 增强模式（小码/虚焦）
        if (enhanceMode != SettingsManager.ENHANCE_OFF) {
            // 标准：对比度增强
            result = tryDecodeEnhanced(w, h, pixels, strong = false)
            if (result != null) return result

            // 增强：锐化+对比度
            if (enhanceMode == SettingsManager.ENHANCE_STRONG) {
                result = tryDecodeEnhanced(w, h, pixels, strong = true)
                if (result != null) return result
            }
        }

        // 4. 旋转识别（最后，因为慢）
        result = tryDecodeRotated(w, h, pixels, 90)
        if (result != null) return result

        result = tryDecodeRotated(w, h, pixels, 180)
        return result
    }

    private fun tryDecode(w: Int, h: Int, pixels: IntArray, invert: Boolean): DecodeResult? {
        return try {
            val src = RGBLuminanceSource(w, h, pixels)
            val source = if (invert) src.invert() else src
            val binary = BinaryBitmap(HybridBinarizer(source))
            val r = reader.decode(binary)
            DecodeResult(r.text, r.resultPoints?.let { pts -> pts.flatMap { listOf(it.x, it.y) }.toFloatArray() })
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun tryDecodeRotated(w: Int, h: Int, pixels: IntArray, degrees: Int): DecodeResult? {
        return try {
            val rotated = rotatePixels(pixels, w, h, degrees)
            val src = RGBLuminanceSource(rotated.width, rotated.height, rotated.pixels)
            val binary = BinaryBitmap(HybridBinarizer(src))
            val r = reader.decode(binary)
            DecodeResult(r.text, r.resultPoints?.let { pts -> pts.flatMap { listOf(it.x, it.y) }.toFloatArray() })
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun tryDecodeEnhanced(w: Int, h: Int, pixels: IntArray, strong: Boolean = false): DecodeResult? {
        return try {
            val enhanced = IntArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                var r = Color.red(c)
                var g = Color.green(c)
                var b = Color.blue(c)
                // 对比度增强
                r = (r * 1.5f).toInt().coerceIn(0, 255)
                g = (g * 1.5f).toInt().coerceIn(0, 255)
                b = (b * 1.5f).toInt().coerceIn(0, 255)
                // 锐化（strong模式）：中心像素加权
                if (strong && i > w && i < pixels.size - w && i % w in 1 until w - 1) {
                    val center = pixels[i]
                    val left = pixels[i - 1]
                    val right = pixels[i + 1]
                    val top = pixels[i - w]
                    val bottom = pixels[i + w]
                    val centerR = Color.red(center)
                    val centerG = Color.green(center)
                    val centerB = Color.blue(center)
                    val avgR = (Color.red(left) + Color.red(right) + Color.red(top) + Color.red(bottom)) / 4
                    val avgG = (Color.green(left) + Color.green(right) + Color.green(top) + Color.green(bottom)) / 4
                    val avgB = (Color.blue(left) + Color.blue(right) + Color.blue(top) + Color.blue(bottom)) / 4
                    // 锐化：中心 - 周围*0.5
                    r = (centerR * 1.5f - avgR * 0.5f).toInt().coerceIn(0, 255)
                    g = (centerG * 1.5f - avgG * 0.5f).toInt().coerceIn(0, 255)
                    b = (centerB * 1.5f - avgB * 0.5f).toInt().coerceIn(0, 255)
                }
                enhanced[i] = Color.rgb(r, g, b)
            }
            val src = RGBLuminanceSource(w, h, enhanced)
            val binary = BinaryBitmap(HybridBinarizer(src))
            val r = reader.decode(binary)
            DecodeResult(r.text, r.resultPoints?.let { pts -> pts.flatMap { listOf(it.x, it.y) }.toFloatArray() })
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
