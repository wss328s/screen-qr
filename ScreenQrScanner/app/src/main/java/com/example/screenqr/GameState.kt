package com.example.screenqr

/**
 * 比赛导航状态管理：4个岔路口，每个3个码，按扫描顺序记录，满3个比大小选最小。
 * 满3个后停下来等用户确认，手动点"下一组"才开始下一个岔路。
 */
object GameState {

    data class Leg(
        val codes: MutableList<Int> = mutableListOf()
    ) {
        val isComplete: Boolean get() = codes.size == 3
        fun recommendedIndex(): Int? {
            if (!isComplete) return null
            return codes.indexOf(codes.minOrNull()!!)
        }
        fun recommendedValue(): Int? {
            if (!isComplete) return null
            return codes.minOrNull()
        }
    }

    val legs = Array(4) { Leg() }
    var currentLegIndex = 0
        private set
    var totalRead = 0
        private set
    var currentLegDone = false
        private set

    fun reset() {
        legs.forEach { it.codes.clear() }
        currentLegIndex = 0
        totalRead = 0
        currentLegDone = false
    }

    fun advance() {
        if (currentLegIndex < 3) {
            currentLegIndex++
            currentLegDone = false
        }
    }

    /**
     * 从内容里提取数字，按扫描顺序追加到当前组。
     * 两个码之间至少间隔2.5秒，避免重复扫到同一个。
     */
    private var lastRecordTime = 0L
    private val cooldownMs = 2000L

    fun parseAndRecord(raw: String): Boolean {
        if (currentLegDone) return false
        val now = System.currentTimeMillis()
        if (now - lastRecordTime < cooldownMs) return false // 冷却中
        val s = raw.trim()
        val numStr = Regex("\\d+").find(s)?.value ?: return false
        val value = numStr.toIntOrNull() ?: return false

        val leg = legs[currentLegIndex]
        if (leg.codes.size >= 3) return false

        leg.codes.add(value)
        totalRead++
        lastRecordTime = now

        if (leg.isComplete) {
            currentLegDone = true
        }
        return true
    }

    fun statusText(): String {
        val legNum = currentLegIndex + 1
        val leg = legs[currentLegIndex]
        val sb = StringBuilder()
        sb.append("岔路$legNum/4 · 已读$totalRead/12\n")

        // 显示已扫的码
        val parts = leg.codes.mapIndexed { i, v -> "码${i+1}:$v" }
        val display = parts.ifEmpty { listOf("码1:?","码2:?","码3:?") }
        sb.append(display.joinToString("  "))

        if (currentLegDone && leg.isComplete) {
            sb.append("\n✓ 推荐走第${leg.recommendedIndex()!!+1}个（${leg.recommendedValue()}最小）")
        } else {
            val need = 3 - leg.codes.size
            sb.append("\n还差${need}个码")
        }
        return sb.toString()
    }

    fun fullSummary(): String {
        val sb = StringBuilder()
        legs.forEachIndexed { i, leg ->
            sb.append("岔路${i + 1}: ")
            if (leg.isComplete) {
                sb.append(leg.codes.joinToString(" "))
                sb.append(" → 走第${leg.recommendedIndex()!!+1}个（${leg.recommendedValue()}最小）")
            } else {
                sb.append("已识别 ${leg.codes.size}/3")
                leg.codes.forEachIndexed { i, v -> sb.append(" 码${i+1}:$v") }
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
