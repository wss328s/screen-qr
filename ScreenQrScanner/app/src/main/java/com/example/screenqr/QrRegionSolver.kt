package com.example.screenqr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 从 Finder Pattern 候选点推算二维码包围盒。
 *
 * QR 的三个定位图案呈等腰直角三角形排列（左上、右上、左下）。
 * 找到三条边最接近「两两垂直且等长」的三个点，就能确定码的位置与朝向，
 * 再按几何关系外扩出完整二维码（含静默区），得到**只包含二维码的小矩形**。
 *
 * 关键收益：后续只需解码这块小区域并放大，远处小码也能识别。
 */
object QrRegionSolver {

    /** 像素矩形（左闭右开） */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isValid: Boolean get() = width >= 16 && height >= 16
    }

    /**
     * 从候选点中挑出最可能的三个定位图案，返回二维码外接矩形。
     *
     * @param candidates 检测到的候选中心
     * @param imgW/imgH  源图尺寸（用于边界裁剪）
     * @param expandRatio 外扩比例（QR 边界到定位图案中心还有 3.5 模块余量）
     * @return 二维码区域；无法确定时返回 null
     */
    fun solve(
        candidates: List<FindPatternDetector.Candidate>,
        imgW: Int,
        imgH: Int,
        expandRatio: Float = 1.15f
    ): Rect? {
        if (candidates.size < 3) return null

        // 候选过多时只取最亮的若干（按 moduleSize 排序，大者更可能是真码）
        val pool = if (candidates.size > 12) {
            candidates.sortedByDescending { it.moduleSize }.take(12)
        } else candidates

        var best: Triple<FindPatternDetector.Candidate, FindPatternDetector.Candidate, FindPatternDetector.Candidate>? = null
        var bestScore = Float.MAX_VALUE
        var bestSide = 0f

        // 枚举三元组，找最接近直角三角形的一组
        val n = pool.size
        for (i in 0 until n - 2) {
            for (j in i + 1 until n - 1) {
                for (k in j + 1 until n) {
                    val a = pool[i]; val b = pool[j]; val c = pool[k]
                    val ab = dist(a, b); val ac = dist(a, c); val bc = dist(b, c)
                    // 去掉最长边，剩下两边应近似相等且垂直
                    val sides = floatArrayOf(ab, ac, bc)
                    sides.sort()
                    val s1 = sides[0]; val s2 = sides[1]
                    if (s1 < 20f) continue
                    val equalErr = abs(s1 - s2) / max(s1, s2)
                    // 直角校验：c² ≈ a² + b²
                    val hyp = sides[2]
                    val rightErr = abs(hyp * hyp - (s1 * s1 + s2 * s2)) / (hyp * hyp)
                    val moduleErr = abs(a.moduleSize - b.moduleSize) + abs(b.moduleSize - c.moduleSize)
                    val score = equalErr * 2f + rightErr * 3f + moduleErr * 0.5f
                    if (score < bestScore) {
                        bestScore = score
                        best = Triple(a, b, c)
                        bestSide = (s1 + s2) / 2f
                    }
                }
            }
        }

        // 评分过差视为误检（图传画面噪点较多，阈值放宽但不无限）
        val picked = best
        if (picked == null || bestScore > 1.2f) return null

        val a = picked.first
        val b = picked.second
        val c = picked.third
        // 直角顶点 = 到另外两点距离和最小者（对角点距离最长）
        val dAB = dist(a, b); val dAC = dist(a, c); val dBC = dist(b, c)
        val corner = when {
            dAB >= dAC && dAB >= dBC -> c   // c 是对角
            dAC >= dAB && dAC >= dBC -> b   // b 是对角
            else -> a                        // a 是对角
        }
        val others = listOf(a, b, c).filter { it !== corner }

        // 定位图案中心到 QR 边界：3.5 模块；模块大小由 moduleSize 给出
        val module = (a.moduleSize + b.moduleSize + c.moduleSize) / 3f
        // QR 边长（模块数）估算：两定位中心距离 ≈ (size - 7) 模块
        val sideModules = (bestSide / max(module, 0.5f)) + 7f
        // 中心到外边缘 = sideModules/2 模块
        val halfExtent = sideModules / 2f * module * expandRatio
        // 静默区余量
        val pad = max(4f, module * 2f)

        // 以三个定位点中心的重心为码中心，按最大跨度外扩
        val cx = (a.x + b.x + c.x) / 3f
        val cy = (a.y + b.y + c.y) / 3f
        // 码中心到定位图案中心的偏移：约 (sideModules/2 - 3.5) 模块
        val centerOffsetModules = max(0f, sideModules / 2f - 3.5f)
        val centerOffset = centerOffsetModules * module

        // 沿「角点 → 重心」方向偏移，得到真实几何中心
        val dx = cx - corner.x
        val dy = cy - corner.y
        val len = sqrt(dx * dx + dy * dy)
        val realCx: Float
        val realCy: Float
        if (len > 1f) {
            realCx = cx + dx / len * centerOffset * 0.33f
            realCy = cy + dy / len * centerOffset * 0.33f
        } else {
            realCx = cx; realCy = cy
        }

        val half = halfExtent + pad
        val left = (realCx - half).toInt().coerceIn(0, imgW - 1)
        val top = (realCy - half).toInt().coerceIn(0, imgH - 1)
        val right = (realCx + half).toInt().coerceIn(left + 1, imgW)
        val bottom = (realCy + half).toInt().coerceIn(top + 1, imgH)

        val rect = Rect(left, top, right, bottom)
        return if (rect.isValid) rect else null
    }

    private fun dist(a: FindPatternDetector.Candidate, b: FindPatternDetector.Candidate): Float {
        val dx = (a.x - b.x).toFloat()
        val dy = (a.y - b.y).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    /** 兜底：无定位图案时，返回图片中心区域（供降级路径使用） */
    fun centerRect(imgW: Int, imgH: Int, ratio: Float = 0.6f): Rect {
        val w = (imgW * ratio).toInt().coerceAtLeast(16)
        val h = (imgH * ratio).toInt().coerceAtLeast(16)
        val l = (imgW - w) / 2
        val t = (imgH - h) / 2
        return Rect(l, t, l + w, t + h)
    }
}
