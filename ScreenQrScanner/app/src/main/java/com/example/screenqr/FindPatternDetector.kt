package com.example.screenqr

/**
 * 二维码定位图案（Finder Pattern）检测器。
 *
 * 为什么需要它：
 * 图传回传画面里二维码常常只占屏幕很小一块（飞机离码远），直接全屏解码时
 * 二维码被缩放糊掉，native 解码器找不到码。而 QR 的定位图案有极强的固定特征：
 * 任意一次横/纵扫描穿过定位图案中心，黑白游程宽度比必定是 **1:1:3:1:1**。
 * 先做廉价的游程扫描找出这些候选中心，再按「三个一组」校验，就能在
 * 极低开销下把二维码位置圈出来。
 *
 * 后续：
 * - [QrRegionSolver] 用定位点推算二维码包围盒（含透视外扩）
 * - [FrameExtractor] 只裁剪这一小块并放大 → 小码变清晰，解码又快又准
 */
object FindPatternDetector {

    /** 一个候选定位图案中心（灰度图坐标） */
    data class Candidate(
        val x: Int,
        val y: Int,
        /** 检测到的模块宽度估计（定位图案 7 模块宽，用于估算 QR 尺寸） */
        val moduleSize: Float
    )

    /** 扫描出的候选点集合，附带图像尺寸 */
    data class Result(
        val candidates: List<Candidate>,
        val width: Int,
        val height: Int
    ) {
        val isEmpty: Boolean get() = candidates.size < 3
    }

    // 游程比例容差（图传画面有模糊/压缩噪声，容差不能太小）
    private const val RATIO_TOLERANCE = 0.5f

    /**
     * 在灰度图上做水平+垂直游程扫描，找出定位图案候选中心。
     *
     * @param gray       灰度数据（1 字节/像素）
     * @param rowStride  每行字节数
     * @param width      图像宽
     * @param height     图像高
     * @param maxCandidates 候选上限，防止噪点导致候选爆炸
     */
    fun detect(
        gray: java.nio.ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        maxCandidates: Int = 256
    ): Result {
        val samples = ByteArray(width * height)
        // 拷贝到连续数组（逐字节 ByteBuffer.get 太慢，一次性 bulk）
        gray.rewind()
        if (rowStride == width) {
            gray.get(samples, 0, width * height)
        } else {
            for (y in 0 until height) {
                gray.position(y * rowStride)
                gray.get(samples, y * width, width)
            }
        }
        gray.rewind()

        // 二值化阈值：用全局均值（图传画面整体亮度可比性尚可，且极快）
        val threshold = meanThreshold(samples, width * height)

        val found = ArrayList<Candidate>(16)
        // 1) 水平扫描
        horizontalScan(samples, width, height, threshold, found, maxCandidates)
        if (found.size >= 3) {
            return Result(found, width, height)
        }
        // 2) 水平不足时补垂直扫描（应对旋转/倾斜的码）
        verticalScan(samples, width, height, threshold, found, maxCandidates)
        return Result(found, width, height)
    }

    /** 全图均值作为二值化阈值（含 10% 余量向暗侧偏移，提升低对比画面稳定性） */
    private fun meanThreshold(samples: ByteArray, n: Int): Int {
        if (n == 0) return 128
        var sum = 0L
        var i = 0
        // 抽样求和即可（每 16 像素取 1），足够稳定且极快
        val step = 16
        var count = 0
        while (i < n) {
            sum += (samples[i].toInt() and 0xFF)
            count++
            i += step
        }
        if (count == 0) return 128
        return (sum / count).toInt()
    }

    /**
     * 水平方向游程扫描：对每一行，统计连续黑白游程长度，
     * 用滑动窗口检查 5 段游程是否满足 1:1:3:1:1。
     */
    private fun horizontalScan(
        s: ByteArray, w: Int, h: Int, threshold: Int,
        out: MutableList<Candidate>, maxCandidates: Int
    ) {
        // 行采样步长：小图逐行扫，大图隔行扫（保证速度）
        val rowStep = if (h > 720) 2 else 1
        val runs = IntArray(5)
        for (y in 0 until h step rowStep) {
            val base = y * w
            var prev = (s[base].toInt() and 0xFF) < threshold
            var runIdx = 0
            runs[0] = 1
            var x = 1
            while (x < w) {
                val dark = (s[base + x].toInt() and 0xFF) < threshold
                if (dark == prev) {
                    runs[runIdx]++
                } else {
                    if (runIdx == 4) {
                        // 已有 5 段，检查比例
                        checkRatio(runs, x - 1, y, out, maxCandidates)?.let { return }
                        // 滑动：整体左移 2 段
                        runs[0] = runs[2]; runs[1] = runs[3]; runs[2] = runs[4]
                        runIdx = 2
                    } else {
                        runIdx++
                    }
                    runs[runIdx] = 1
                    prev = dark
                }
                x++
            }
            if (runIdx == 4 && checkRatio(runs, w - 1, y, out, maxCandidates)) return
        }
    }

    /** 垂直方向游程扫描（同水平逻辑，转置遍历） */
    private fun verticalScan(
        s: ByteArray, w: Int, h: Int, threshold: Int,
        out: MutableList<Candidate>, maxCandidates: Int
    ) {
        val colStep = if (w > 720) 2 else 1
        val runs = IntArray(5)
        for (x in 0 until w step colStep) {
            var runIdx = 0
            var prev = (s[x].toInt() and 0xFF) < threshold
            runs[0] = 1
            var y = 1
            while (y < h) {
                val dark = (s[y * w + x].toInt() and 0xFF) < threshold
                if (dark == prev) {
                    runs[runIdx]++
                } else {
                    if (runIdx == 4) {
                        if (checkRatioVRuns(runs, x, y - 1, out, maxCandidates)) return
                        runs[0] = runs[2]; runs[1] = runs[3]; runs[2] = runs[4]
                        runIdx = 2
                    } else {
                        runIdx++
                    }
                    runs[runIdx] = 1
                    prev = dark
                }
                y++
            }
            if (runIdx == 4 && checkRatioVRuns(runs, x, h - 1, out, maxCandidates)) return
        }
    }

    /**
     * 检查游程是否满足 1:1:3:1:1；满足则记录中心点。
     * @return true 表示候选已达上限，调用方应停止扫描
     */
    private fun checkRatio(
        r: IntArray, endX: Int, y: Int, out: MutableList<Candidate>, maxCandidates: Int
    ): Boolean {
        return checkRatioVRuns(r, endX, y, out, maxCandidates)
    }

    /**
     * 游程比例校验（水平/垂直共用）。
     * 要求：5 段总和 ≥ 7（至少 7 个像素，对应 7 模块的最小可辨尺度），
     * 且 1:1:3:1:1 在容差内成立。中心为第 3 段中点。
     *
     * @return true 表示候选已达上限，调用方应停止扫描
     */
    private fun checkRatioVRuns(
        r: IntArray, cx: Int, cy: Int, out: MutableList<Candidate>, maxCandidates: Int
    ): Boolean {
        val total = r[0] + r[1] + r[2] + r[3] + r[4]
        if (total < 7) return false
        val module = total / 7f
        val maxVariance = module * RATIO_TOLERANCE
        fun near(v: Int, expect: Float) = kotlin.math.abs(v - expect) <= maxVariance
        if (!near(r[0], module) || !near(r[1], module) || !near(r[2], module * 3) ||
            !near(r[3], module) || !near(r[4], module)
        ) return false
        // 中心落在第 3 段内部
        val centerOffset = r[2] / 2
        val center = cx - (r[4] + r[3] + centerOffset)
        out.add(Candidate(center, cy, module))
        return out.size >= maxCandidates
    }
}
