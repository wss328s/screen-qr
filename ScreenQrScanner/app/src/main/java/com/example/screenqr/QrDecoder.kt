package com.example.screenqr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.detector.Detector

/**
 * 强化识别：返回内容+位置坐标。
 * 针对：模糊（锐化）、反光/光照不均（局部二值化/全局二值化/反色）、
 * 对比度低（对比度拉伸）、畸形/旋转（多角度尝试）。
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

    fun decode(bitmap: Bitmap, enhanceMode: Int = SettingsManager.ENHANCE_STD): DecodeResult? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. 原图：Hybrid（局部自适应，抗反光/光照不均）
        tryDecode(w, h, pixels, false, true)?.let { return it }

        // 2. 原图：Global（全局阈值，有时比局部更稳）
        tryDecode(w, h, pixels, false, false)?.let { return it }

        // 3. 反色：Hybrid（白码黑底/深色码）
        tryDecode(w, h, pixels, true, true)?.let { return it }

        // 4. 反色：Global
        tryDecode(w, h, pixels, true, false)?.let { return it }

        if (enhanceMode != SettingsManager.ENHANCE_OFF) {
            // 5. 对比度拉伸（低对比/发灰）
            val contrast = contrastStretch(pixels)
            tryDecode(w, h, contrast, false, true)?.let { return it }
            tryDecode(w, h, contrast, false, false)?.let { return it }

            // 6. 反光抑制（压缩高亮）+ 局部二值化
            val antiGlare = suppressGlare(pixels)
            tryDecode(w, h, antiGlare, false, true)?.let { return it }

            if (enhanceMode == SettingsManager.ENHANCE_STRONG) {
                // 7. 锐化（虚焦/模糊）
                val sharpened = sharpen(pixels, w, h)
                tryDecode(w, h, sharpened, false, true)?.let { return it }
                tryDecode(w, h, sharpened, false, false)?.let { return it }

                // 8. 自适应分块二值化（强反光/阴影）
                val adaptive = adaptiveThreshold(pixels, w, h)
                tryDecode(w, h, adaptive, false, true)?.let { return it }

                // 8.5 倾斜：任意角度（图传画面非正对），配合锐化
                for (ang in listOf(30f, 45f, 60f)) {
                    val rot = rotateFree(pixels, w, h, ang)
                    tryDecode(rot.width, rot.height, rot.pixels, false, true)?.let { return it }
                    val rotS = sharpen(rot.pixels, rot.width, rot.height)
                    tryDecode(rot.width, rot.height, rotS, false, true)?.let { return it }
                }
            }
        }

        // 9. 旋转（畸形/倒置），最后做（慢）
        tryDecodeRotated(w, h, pixels, 90)?.let { return it }
        tryDecodeRotated(w, h, pixels, 180)?.let { return it }
        tryDecodeRotated(w, h, pixels, 270)?.let { return it }

        return null
    }

    /**
     * 疑似检测：能定位到二维码但没解码成功。
     * 用于"疑似码未解析"提示。
     */
    fun detectSuspect(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return try {
            val src = RGBLuminanceSource(w, h, pixels)
            val bits = HybridBinarizer(src).blackMatrix
            Detector(bits).detect()
            true
        } catch (e: Exception) {
            try {
                val src = RGBLuminanceSource(w, h, pixels)
                val bits = GlobalHistogramBinarizer(src).blackMatrix
                Detector(bits).detect()
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun toResult(r: com.google.zxing.Result): DecodeResult =
        DecodeResult(
            r.text,
            r.resultPoints?.let { pts -> pts.flatMap { listOf(it.x, it.y) }.toFloatArray() }
        )

    private fun tryDecode(w: Int, h: Int, pixels: IntArray, invert: Boolean, hybrid: Boolean): DecodeResult? {
        return try {
            var src: com.google.zxing.LuminanceSource = RGBLuminanceSource(w, h, pixels)
            if (invert) src = src.invert()
            val binary = if (hybrid) BinaryBitmap(HybridBinarizer(src))
                         else BinaryBitmap(GlobalHistogramBinarizer(src))
            toResult(reader.decode(binary))
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun tryDecodeRotated(w: Int, h: Int, pixels: IntArray, degrees: Int): DecodeResult? {
        return try {
            val r = rotatePixels(pixels, w, h, degrees)
            val src = RGBLuminanceSource(r.width, r.height, r.pixels)
            val binary = BinaryBitmap(HybridBinarizer(src))
            toResult(reader.decode(binary))
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    // ---- 预处理 ----

    private fun gray(c: Int): Int =
        (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f).toInt()

    /** 对比度拉伸：把灰度范围拉满 0-255 */
    private fun contrastStretch(pixels: IntArray): IntArray {
        var min = 255; var max = 0
        for (c in pixels) {
            val g = gray(c)
            if (g < min) min = g
            if (g > max) max = g
        }
        if (max - min < 1) return pixels
        val scale = 255f / (max - min)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val g = ((gray(pixels[i]) - min) * scale).toInt().coerceIn(0, 255)
            out[i] = Color.rgb(g, g, g)
        }
        return out
    }

    /** 反光抑制：压缩高亮区域，保留暗部细节 */
    private fun suppressGlare(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val g = gray(pixels[i])
            // 高亮部分用 gamma 压暗
            val ng = if (g > 180) {
                (180 + (g - 180) * 0.4f).toInt()
            } else g
            out[i] = Color.rgb(ng, ng, ng)
        }
        return out
    }

    /** 锐化：3x3 卷积，处理虚焦/模糊 */
    private fun sharpen(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = pixels.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = gray(pixels[i])
                val l = gray(pixels[i - 1]); val rr = gray(pixels[i + 1])
                val u = gray(pixels[i - w]); val d = gray(pixels[i + w])
                // 锐化核：中心5，四邻-1
                var v = 5 * c - l - rr - u - d
                v = v.coerceIn(0, 255)
                out[i] = Color.rgb(v, v, v)
            }
        }
        return out
    }

    /** 自适应分块二值化：处理强反光/阴影（每块按局部均值） */
    private fun adaptiveThreshold(pixels: IntArray, w: Int, h: Int): IntArray {
        val block = 24
        val out = IntArray(pixels.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x0 = maxOf(0, x - block / 2); val x1 = minOf(w - 1, x + block / 2)
                val y0 = maxOf(0, y - block / 2); val y1 = minOf(h - 1, y + block / 2)
                var sum = 0; var n = 0
                var yy = y0
                while (yy <= y1) {
                    var xx = x0
                    while (xx <= x1) { sum += gray(pixels[yy * w + xx]); n++; xx++ }
                    yy++
                }
                val mean = sum / n - 7
                val g = gray(pixels[y * w + x])
                val v = if (g < mean) 0 else 255
                out[y * w + x] = Color.rgb(v, v, v)
            }
        }
        return out
    }

    private fun rotateFree(pixels: IntArray, w: Int, h: Int, angleDeg: Float): RotatedData {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cos = Math.cos(rad); val sin = Math.sin(rad)
        // 输出尺寸：旋转后扩展到能容纳整图
        val outW = (Math.abs(w * cos) + Math.abs(h * sin)).toInt().coerceAtLeast(1)
        val outH = (Math.abs(w * sin) + Math.abs(h * cos)).toInt().coerceAtLeast(1)
        val out = IntArray(outW * outH) { Color.BLACK }

        val cx = w / 2.0; val cy = h / 2.0
        val ocx = outW / 2.0; val ocy = outH / 2.0

        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                // 反算源坐标
                val dx = ox - ocx; val dy = oy - ocy
                val sx = (cos * dx + sin * dy + cx).toInt()
                val sy = (-sin * dx + cos * dy + cy).toInt()
                if (sx in 0 until w && sy in 0 until h) {
                    out[oy * outW + ox] = pixels[sy * w + sx]
                }
            }
        }
        return RotatedData(outW, outH, out)
    }

    private fun rotatePixels(pixels: IntArray, w: Int, h: Int, degrees: Int): RotatedData {
        return when (degrees) {
            90, 270 -> {
                val np = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        if (degrees == 90)
                            np[x * h + (h - 1 - y)] = pixels[y * w + x]
                        else
                            np[(w - 1 - x) * h + y] = pixels[y * w + x]
                    }
                }
                RotatedData(h, w, np)
            }
            180 -> {
                val np = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        np[(h - 1 - y) * w + (w - 1 - x)] = pixels[y * w + x]
                    }
                }
                RotatedData(w, h, np)
            }
            else -> RotatedData(w, h, pixels)
        }
    }

    private data class RotatedData(val width: Int, val height: Int, val pixels: IntArray)
}
