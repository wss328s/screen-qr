package com.example.screenqr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer

/**
 * 识别解码器：在"识别率"和"速度/稳定"之间分级。
 *
 * 设计：
 * - 截屏像素一次性转灰度，预处理都在灰度上做，避免 RGB->灰度 往返；
 * - 用自定义 ArrayLuminanceSource 直接喂灰度给 ZXing；
 * - 分级快速失败：基础（原图/反色） -> 标准（对比度/反光） -> 增强（锐化/自适应/自由旋转）；
 * - 90/180/270 放最后（倒置码常见，成本低）。
 *
 * 针对：模糊（锐化）、反光/阴影（反光抑制、自适应、反色）、低对比（对比度拉伸）、
 * 畸形/倾斜（任意角度、90/180/270）。
 */
object QrDecoder {

    data class DecodeResult(
        val text: String,
        val points: FloatArray? = null
    )

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
    }

    fun decode(bitmap: android.graphics.Bitmap, enhanceMode: Int = SettingsManager.ENHANCE_STD): DecodeResult? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 一次性转灰度
        val gray = toGray(pixels)

        // 1. 原图 Hybrid（最常见，最快）
        tryDecode(w, h, gray, false)?.let { return it }
        // 2. 反色 Hybrid（白码黑底）
        tryDecode(w, h, gray, true)?.let { return it }

        if (enhanceMode != SettingsManager.ENHANCE_OFF) {
            // 3. 对比度拉伸（发灰/低对比）
            val contrast = contrastStretch(gray)
            tryDecode(w, h, contrast, false)?.let { return it }

            // 4. 反光抑制（压缩高亮，保留暗部）
            val antiGlare = suppressGlare(gray)
            tryDecode(w, h, antiGlare, false)?.let { return it }

            if (enhanceMode == SettingsManager.ENHANCE_STRONG) {
                // 5. 锐化（虚焦/模糊）
                val sharpened = sharpen(gray, w, h)
                tryDecode(w, h, sharpened, false)?.let { return it }

                // 6. 自适应二值化（积分图，O(n)，强反光/阴影）
                val adaptive = adaptiveThreshold(gray, w, h)
                tryDecode(w, h, adaptive, false)?.let { return it }

                // 7. 任意角度倾斜（图传非正对）
                for (ang in listOf(30f, 45f, 60f)) {
                    val rot = rotateFree(gray, w, h, ang)
                    tryDecode(rot.width, rot.height, rot.pixels, false)?.let { return it }
                }
            }
        }

        // 8. 90/180/270（倒置/侧置，最后做，成本低）
        tryDecodeRotated(w, h, gray, 90)?.let { return it }
        tryDecodeRotated(w, h, gray, 180)?.let { return it }
        tryDecodeRotated(w, h, gray, 270)?.let { return it }

        return null
    }

    // ---- 识别 ----

    private fun toResult(r: com.google.zxing.Result): DecodeResult =
        DecodeResult(
            r.text,
            r.resultPoints?.let { pts -> pts.flatMap { listOf(it.x, it.y) }.toFloatArray() }
        )

    private fun tryDecode(w: Int, h: Int, gray: IntArray, invert: Boolean): DecodeResult? {
        return try {
            var src: LuminanceSource = ArrayLuminanceSource(w, h, toBytes(gray))
            if (invert) src = src.invert()
            val binary = BinaryBitmap(HybridBinarizer(src))
            toResult(reader.decode(binary))
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun tryDecodeRotated(w: Int, h: Int, gray: IntArray, degrees: Int): DecodeResult? {
        return try {
            val r = rotatePixels(gray, w, h, degrees)
            val src = ArrayLuminanceSource(r.width, r.height, toBytes(r.pixels))
            val binary = BinaryBitmap(HybridBinarizer(src))
            toResult(reader.decode(binary))
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    // ---- 灰度与预处理（整数运算）----

    /** RGB像素 -> 灰度（整数加权） */
    private fun toGray(pixels: IntArray): IntArray {
        val g = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = c shr 16 and 0xFF
            val gg = c shr 8 and 0xFF
            val b = c and 0xFF
            g[i] = (r * 299 + gg * 587 + b * 114) / 1000
        }
        return g
    }

    /** 灰度 IntArray -> ZXing 需要的 ByteArray（按无符号字节） */
    private fun toBytes(g: IntArray): ByteArray {
        val b = ByteArray(g.size)
        for (i in g.indices) b[i] = g[i].toByte()
        return b
    }

    /** 对比度拉伸：把灰度范围拉满 0-255 */
    private fun contrastStretch(gray: IntArray): IntArray {
        var min = 255; var max = 0
        for (g in gray) {
            if (g < min) min = g
            if (g > max) max = g
        }
        if (max - min < 1) return gray
        val range = max - min
        val out = IntArray(gray.size)
        for (i in gray.indices) {
            out[i] = ((gray[i] - min) * 255 / range).coerceIn(0, 255)
        }
        return out
    }

    /** 反光抑制：压缩高亮区域，保留暗部细节 */
    private fun suppressGlare(gray: IntArray): IntArray {
        val out = IntArray(gray.size)
        for (i in gray.indices) {
            val g = gray[i]
            out[i] = if (g > 180) 180 + (g - 180) * 2 / 5 else g
        }
        return out
    }

    /** 锐化：3x3 卷积，处理虚焦/模糊 */
    private fun sharpen(gray: IntArray, w: Int, h: Int): IntArray {
        val out = gray.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = gray[i]
                val v = 5 * c - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
                out[i] = v.coerceIn(0, 255)
            }
        }
        return out
    }

    /** 自适应二值化：积分图 O(n)，处理强反光/阴影（局部均值） */
    private fun adaptiveThreshold(gray: IntArray, w: Int, h: Int): IntArray {
        // 积分图（用Long防止溢出）
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 1..h) {
            var rowSum = 0L
            for (x in 1..w) {
                rowSum += gray[(y - 1) * w + (x - 1)]
                integral[y * (w + 1) + x] = integral[(y - 1) * (w + 1) + x] + rowSum
            }
        }

        val half = 12  // 窗口半径
        val out = IntArray(gray.size)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - half)
            val y1 = minOf(h - 1, y + half)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - half)
                val x1 = minOf(w - 1, x + half)
                val area = (y1 - y0 + 1) * (x1 - x0 + 1)
                val sum = integral[(y1 + 1) * (w + 1) + (x1 + 1)] -
                          integral[y0 * (w + 1) + (x1 + 1)] -
                          integral[(y1 + 1) * (w + 1) + x0] +
                          integral[y0 * (w + 1) + x0]
                val mean = (sum / area).toInt() - 7
                val v = if (gray[y * w + x] < mean) 0 else 255
                out[y * w + x] = v
            }
        }
        return out
    }

    /** 任意角度旋转（输出扩展到能容纳整图） */
    private fun rotateFree(gray: IntArray, w: Int, h: Int, angleDeg: Float): RotatedData {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cos = Math.cos(rad); val sin = Math.sin(rad)
        val outW = (Math.abs(w * cos) + Math.abs(h * sin)).toInt().coerceAtLeast(1)
        val outH = (Math.abs(w * sin) + Math.abs(h * cos)).toInt().coerceAtLeast(1)
        val out = IntArray(outW * outH)  // 默认0=黑

        val cx = w / 2.0; val cy = h / 2.0
        val ocx = outW / 2.0; val ocy = outH / 2.0

        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                val dx = ox - ocx; val dy = oy - ocy
                val sx = (cos * dx + sin * dy + cx).toInt()
                val sy = (-sin * dx + cos * dy + cy).toInt()
                if (sx in 0 until w && sy in 0 until h) {
                    out[oy * outW + ox] = gray[sy * w + sx]
                }
            }
        }
        return RotatedData(outW, outH, out)
    }

    /** 90/180/270 旋转 */
    private fun rotatePixels(gray: IntArray, w: Int, h: Int, degrees: Int): RotatedData {
        return when (degrees) {
            90, 270 -> {
                val np = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        if (degrees == 90)
                            np[x * h + (h - 1 - y)] = gray[y * w + x]
                        else
                            np[(w - 1 - x) * h + y] = gray[y * w + x]
                    }
                }
                RotatedData(h, w, np)
            }
            180 -> {
                val np = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        np[(h - 1 - y) * w + (w - 1 - x)] = gray[y * w + x]
                    }
                }
                RotatedData(w, h, np)
            }
            else -> RotatedData(w, h, gray)
        }
    }

    private data class RotatedData(val width: Int, val height: Int, val pixels: IntArray)

    /** 直接接受灰度数组的 LuminanceSource，避免 RGB->灰度 往返 */
    private class ArrayLuminanceSource(
        w: Int,
        h: Int,
        private val data: ByteArray
    ) : LuminanceSource(w, h) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val offset = y * width
            val res = row ?: ByteArray(width)
            System.arraycopy(data, offset, res, 0, width)
            return res
        }

        override fun getMatrix(): ByteArray = data
    }
}
