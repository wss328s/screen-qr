package com.example.screenqr

import java.nio.ByteBuffer

/**
 * 帧提取器：从截屏 RGBA 缓冲区中，按 ROI 单遍完成"裁剪 + 降采样 + 转灰度"，
 * 直接产出可喂给 zxingcpp native（readYBuffer）的灰度数据。
 *
 * 相比"先建全屏 Bitmap -> 再缩放 -> 再让 native 转灰度"的旧路径：
 * - 不创建任何 Bitmap，不分配全屏内存；
 * - 只处理 ROI 内的像素，且只读一次；
 * - 源帧一次性 bulk 拷贝到堆内后做快速数组访问，避免逐像素 JNI 调用；
 * - 输出 1 字节/像素的灰度，数据量是 RGBA 的 1/4。
 *
 * 缓冲管理：每个 GrayFrame 持有独立的直接缓冲，可安全跨线程传递；
 * 用一个小对象池复用（解码完归还），稳态下几乎不分配 native 内存。
 */
object FrameExtractor {

    class GrayFrame(
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val gray: ByteBuffer,
        val capturedAt: Long
    )

    // 复用的源帧拷贝（堆内，避免逐像素 JNI 读取）
    private var srcCopy: ByteArray = ByteArray(0)

    // 空闲灰度直接缓冲池（容量可能大于实际需要，按最小可用复用）
    private val pool = ArrayDeque<ByteBuffer>()
    private const val MAX_POOL = 3

    private fun acquireBuffer(need: Int): ByteBuffer {
        synchronized(pool) {
            var best: ByteBuffer? = null
            for (b in pool) {
                if (b.capacity() >= need && (best == null || b.capacity() < best!!.capacity())) {
                    best = b
                }
            }
            if (best != null) {
                pool.remove(best)
                best!!.clear()
                return best
            }
        }
        return ByteBuffer.allocateDirect(need)
    }

    /** 解码完成后归还帧缓冲，供后续帧复用 */
    fun recycle(frame: GrayFrame) {
        synchronized(pool) {
            if (pool.size < MAX_POOL) pool.addLast(frame.gray)
        }
    }

    /**
     * @param buffer      截屏第一个平面（RGBA）的 ByteBuffer
     * @param srcRowStride 平面 rowStride（字节）
     * @param pixelStride  平面 pixelStride（RGBA 通常为 4）
     * @param srcW/srcH    源图像尺寸
     * @param bounds       ROI 归一化边界 [left, top, right, bottom]
     * @param maxDimension 输出最长边上限（0=不限制）
     */
    fun extract(
        buffer: ByteBuffer,
        srcRowStride: Int,
        pixelStride: Int,
        srcW: Int,
        srcH: Int,
        bounds: FloatArray,
        maxDimension: Int,
        capturedAt: Long
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

        // 1) 一次性把源平面 bulk 拷到堆内（复用 srcCopy），之后全是快速数组访问
        val cap = buffer.capacity()
        if (srcCopy.size < cap) srcCopy = ByteArray(cap)
        buffer.rewind()
        val n = buffer.remaining()
        buffer.get(srcCopy, 0, n)

        // 2) 从池取（或新建）独立输出缓冲
        val outNeeded = outW * outH
        val outBuf = acquireBuffer(outNeeded)

        // 3) 单遍 裁剪 + 降采样 + 转灰度
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
        return GrayFrame(outW, outH, outW, outBuf, capturedAt)
    }
}
