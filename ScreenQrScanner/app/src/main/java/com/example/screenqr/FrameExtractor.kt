package com.example.screenqr

import java.nio.ByteBuffer

/**
 * 帧提取器：从截屏 RGBA 缓冲区中裁剪 + 缩放 + 转灰度，产出可直接喂给
 * zxing-cpp native（readGray）的灰度数据。
 *
 * ## 为什么必须支持「放大」
 *
 * 本项目用于无人机图传画面识别，二维码在屏幕上常常只占很小一块
 * （实测某素材里码仅约 40×50 像素）。若把整屏缩小后解码，码的模块会糊成一团，
 * 解码器根本找不到码 —— 实测缩到 720 宽时命中率只有 0.6%~6.7%，
 * 而放大 2~3 倍后命中率提升到 10.5%~38%。所以放大是必需能力，不是可选优化。
 *
 * ## 缓冲区策略
 *
 * 每次提取写入**独立的缓冲区槽位**（[BUFFER_SLOTS] 个轮转），
 * 避免「上一级的结果被子级复用同一块内存覆盖」——这是必须遵守的约束：
 * [GrayFrame.gray] 持有 buffer 引用，native 侧会按其 rowStride×height 读取，
 * 一旦被覆写就会解出错误数据或直接失败。
 */
object FrameExtractor {

    class GrayFrame(
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val gray: ByteBuffer
    )

    /** 输出缓冲区槽位数（级联解码最多同时持有 3 份不同尺寸的结果） */
    private const val BUFFER_SLOTS = 4

    // 复用的源帧拷贝（堆内，避免逐像素 JNI 读取）
    private var srcCopy: ByteArray = ByteArray(0)
    // 每个槽位独立缓冲，避免互相覆盖
    private val outBufs: Array<ByteBuffer> = Array(BUFFER_SLOTS) { ByteBuffer.allocateDirect(1) }
    // 已拷贝的源字节数
    private var copied: Int = 0

    /** 当前已拷贝的源字节数（供调用方判断有效性） */
    val sourceSize: Int get() = copied

    /**
     * 每帧调用一次：把源平面 bulk 拷贝到堆内，之后多次提取复用，
     * 避免各级解码各拷一遍整帧。
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

    /** 按槽位取缓冲并确保容量 */
    private fun bufferFor(slot: Int, need: Int): ByteBuffer {
        val idx = slot % BUFFER_SLOTS
        var b = outBufs[idx]
        if (b.capacity() < need) {
            b = ByteBuffer.allocateDirect(need)
            outBufs[idx] = b
        }
        return b
    }

    /**
     * 按归一化 ROI 边界提取，输出宽度为 [targetWidth]（可缩小也可放大）。
     *
     * @param bounds      ROI 归一化边界 [left, top, right, bottom]
     * @param targetWidth 目标输出宽度（像素）
     * @param slot        缓冲区槽位（同一级联链路内不要复用同一槽位）
     */
    fun extractToWidth(
        srcRowStride: Int,
        pixelStride: Int,
        srcW: Int,
        srcH: Int,
        bounds: FloatArray,
        targetWidth: Int,
        slot: Int
    ): GrayFrame? {
        val l = (bounds[0] * srcW).toInt().coerceIn(0, srcW - 1)
        val t = (bounds[1] * srcH).toInt().coerceIn(0, srcH - 1)
        val r = (bounds[2] * srcW).toInt().coerceAtLeast(l + 1).coerceAtMost(srcW)
        val b = (bounds[3] * srcH).toInt().coerceAtLeast(t + 1).coerceAtMost(srcH)
        return extractRectToWidth(srcRowStride, pixelStride, srcW, srcH, l, t, r, b, targetWidth, slot)
    }

    /**
     * 按**像素矩形**提取，输出宽度为 [targetWidth]，双线性采样。
     *
     * 直接从源像素采样到目标尺寸（一次遍历），不产生中间缓冲，
     * 因此放大 2~3 倍时也只需一遍扫描。
     */
    fun extractRectToWidth(
        srcRowStride: Int,
        pixelStride: Int,
        srcW: Int,
        srcH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        targetWidth: Int,
        slot: Int
    ): GrayFrame? {
        val l = left.coerceIn(0, srcW - 1)
        val t = top.coerceIn(0, srcH - 1)
        val r = right.coerceAtLeast(l + 1).coerceAtMost(srcW)
        val b = bottom.coerceAtLeast(t + 1).coerceAtMost(srcH)
        val roiW = r - l
        val roiH = b - t
        if (roiW < 8 || roiH < 8) return null

        val outW = targetWidth.coerceAtLeast(32)
        val outH = (roiH.toLong() * outW / roiW).toInt().coerceAtLeast(16)

        val need = outW * outH
        // 超过 native 能承受的规模则放弃（保护内存与耗时）
        if (need > 16_000_000) return null

        val buf = bufferFor(slot, need)
        val n = copied
        val sx = roiW.toFloat() / outW
        val sy = roiH.toFloat() / outH

        // 预计算每一行对应的源行（减少重复乘法）
        for (oy in 0 until outH) {
            val fy = t + (oy + 0.5f) * sy - 0.5f
            var y0 = fy.toInt()
            if (y0 < t) y0 = t
            if (y0 > b - 1) y0 = b - 1
            var y1 = y0 + 1
            if (y1 > b - 1) y1 = b - 1
            val wy = (fy - y0).coerceIn(0f, 1f)
            val row0 = y0 * srcRowStride
            val row1 = y1 * srcRowStride
            val dstRow = oy * outW
            for (ox in 0 until outW) {
                val fx = l + (ox + 0.5f) * sx - 0.5f
                var x0 = fx.toInt()
                if (x0 < l) x0 = l
                if (x0 > r - 1) x0 = r - 1
                var x1 = x0 + 1
                if (x1 > r - 1) x1 = r - 1
                val wx = (fx - x0).coerceIn(0f, 1f)

                val i00 = row0 + x0 * pixelStride
                val i01 = row0 + x1 * pixelStride
                val i10 = row1 + x0 * pixelStride
                val i11 = row1 + x1 * pixelStride

                val v00 = lumAt(i00, n)
                val v01 = lumAt(i01, n)
                val v10 = lumAt(i10, n)
                val v11 = lumAt(i11, n)

                // 双线性插值
                val top0 = v00 + (v01 - v00) * wx
                val bot0 = v10 + (v11 - v10) * wx
                val v = top0 + (bot0 - top0) * wy
                buf.put(dstRow + ox, (v + 0.5f).toInt().toByte())
            }
        }
        buf.limit(need)
        buf.rewind()
        return GrayFrame(outW, outH, outW, buf)
    }

    /** 读取一个像素并转灰度（与 zxing-cpp 核心 RGBToLum 一致） */
    private fun lumAt(idx: Int, n: Int): Float {
        if (idx + 2 < n) {
            val rr = srcCopy[idx].toInt() and 0xFF
            val gg = srcCopy[idx + 1].toInt() and 0xFF
            val bb = srcCopy[idx + 2].toInt() and 0xFF
            return ((306 * rr + 601 * gg + 117 * bb + 0x200) shr 10).toFloat()
        }
        return 0f
    }

    /**
     * 兼容旧调用：按归一化 bounds 提取，限制最长边不超过 maxDimension（只缩不放）。
     * 保留供设置页「分析精度」等场景使用。
     */
    fun extract(
        srcRowStride: Int,
        pixelStride: Int,
        srcW: Int,
        srcH: Int,
        bounds: FloatArray,
        maxDimension: Int
    ): GrayFrame? {
        val roiW = ((bounds[2] - bounds[0]) * srcW)
        if (roiW < 1f) return null
        val target = if (maxDimension > 0) minOf(maxDimension, roiW.toInt().coerceAtLeast(32))
                     else roiW.toInt().coerceAtLeast(32)
        return extractToWidth(srcRowStride, pixelStride, srcW, srcH, bounds, target, 0)
    }
}
