package com.example.screenqr

import java.nio.ByteBuffer

/**
 * 帧提取器：从截屏 RGBA 缓冲区中，按 ROI 单遍完成"裁剪 + 降采样 + 转灰度"，
 * 直接产出可喂给 zxingcpp native（readYBuffer）的灰度数据。
 *
 * 流程：
 * 1) [copySource] 每帧只调用一次，把源平面一次性 bulk 拷贝到堆内（供两级解码复用）；
 * 2) [extract] 可在同一源帧上调用多次（小图快速档 / 全图增强档），复用同一输出缓冲。
 *
 * 相比"先建全屏 Bitmap -> 再缩放 -> 再让 native 转灰度"的旧路径：
 * - 不创建任何 Bitmap，不分配全屏内存；
 * - 只处理 ROI 内的像素，且只读一次；
 * - 输出 1 字节/像素的灰度，数据量是 RGBA 的 1/4。
 */
object FrameExtractor {

    class GrayFrame(
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val gray: ByteBuffer
    )

    // 复用的源帧拷贝（堆内，避免逐像素 JNI 读取）
    private var srcCopy: ByteArray = ByteArray(0)
    // 复用的输出灰度缓冲（直接内存，避免每帧分配 native 内存）
    private var outBuf: ByteBuffer = ByteBuffer.allocateDirect(1)
    // 已拷贝的源字节数
    private var copied: Int = 0

    /**
     * 每帧调用一次：把源平面 bulk 拷贝到堆内，之后多次 [extract] 复用，
     * 避免两级解码各拷一遍全屏。
     *
     * @return 实际拷贝的字节数
     */
    fun copySource(buffer: ByteBuffer): Int {
        val cap = buffer.capacity()
        if (srcCopy.size < cap) srcCopy = ByteArray(cap)
        buffer.rewind()
        val n = buffer.remaining()
        buffer.get(srcCopy, 0, n)
        copied = n
        return n
    }

    /**
     * 基于已拷贝的源帧，按 ROI 裁剪 + 降采样 + 转灰度。
     *
     * @param srcRowStride 平面 rowStride（字节）
     * @param pixelStride  平面 pixelStride（RGBA 通常为 4）
     * @param srcW/srcH    源图像尺寸
     * @param bounds       ROI 归一化边界 [left, top, right, bottom]
     * @param maxDimension 输出最长边上限（0=不限制）
     */
    fun extract(
        srcRowStride: Int,
        pixelStride: Int,
        srcW: Int,
        srcH: Int,
        bounds: FloatArray,
        maxDimension: Int
    ): GrayFrame? {
        // ROI 像素矩形
        val l = (bounds[0] * srcW).toInt().coerceIn(0, srcW - 1)
        val t = (bounds[1] * srcH).toInt().coerceIn(0, srcH - 1)
        val r = (bounds[2] * srcW).toInt().coerceAtLeast(l + 1).coerceAtMost(srcW)
        val b = (bounds[3] * srcH).toInt().coerceAtLeast(t + 1).coerceAtMost(srcH)
        val roiW = r - l
        val roiH = b - t

        // 降采样步长（整数，保证输出最长边不超过 maxDimension）
        var step = 1
        if (maxDimension > 0) {
            val longest = maxOf(roiW, roiH)
            if (longest > maxDimension) {
                step = (longest + maxDimension - 1) / maxDimension
            }
        }
        val outW = (roiW + step - 1) / step
        val outH = (roiH + step - 1) / step
        if (outW < 16 || outH < 16) return null

        // 确保输出直接缓冲足够（复用，避免每帧分配 native 内存；小图/大图共用，按需增长）
        val outNeeded = outW * outH
        if (outBuf.capacity() < outNeeded) {
            outBuf = ByteBuffer.allocateDirect(outNeeded)
        }

        // 单遍 裁剪 + 降采样 + 转灰度
        val n = copied
        for (oy in 0 until outH) {
            val sy = t + oy * step
            val srcRowBase = sy * srcRowStride
            val dstRowBase = oy * outW
            for (ox in 0 until outW) {
                val sx = l + ox * step
                val idx = srcRowBase + sx * pixelStride
                val y = if (idx + 2 < n) {
                    val rr = srcCopy[idx].toInt() and 0xFF
                    val gg = srcCopy[idx + 1].toInt() and 0xFF
                    val bb = srcCopy[idx + 2].toInt() and 0xFF
                    // 与 zxing-cpp 核心 RGBToLum 完全一致：(306R+601G+117B+0x200)>>10
                    (306 * rr + 601 * gg + 117 * bb + 0x200) shr 10
                } else 0
                outBuf.put(dstRowBase + ox, y.toByte())
            }
        }
        outBuf.limit(outNeeded)
        outBuf.rewind()
        return GrayFrame(outW, outH, outW, outBuf)
    }
}
