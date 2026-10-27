package com.example.screenqr

/**
 * 比赛导航状态管理：4个岔路口，每个ABC三个码，识别满3个比较出最小方向。
 * 满3个后停下来等用户确认，手动点"下一组"才开始下一个岔路。
 */
object GameState {

    data class Leg(
        val codes: MutableMap<Char, Int> = mutableMapOf()
    ) {
        val isComplete: Boolean get() = codes.size == 3
        fun recommended(): Char? {
            if (!isComplete) return null
            return codes.entries.minByOrNull { it.value }!!.key
        }
        fun recommendedValue(): Int? {
            if (!isComplete) return null
            return codes.entries.minByOrNull { it.value }!!.value
        }
    }

    val legs = Array(4) { Leg() }
    var currentLegIndex = 0
        private set
    var totalRead = 0
        private set
    // 当前组是否已完成（等用户确认）
    var currentLegDone = false
        private set

    fun reset() {
        legs.forEach { it.codes.clear() }
        currentLegIndex = 0
        totalRead = 0
        currentLegDone = false
    }

    /**
     * 用户点"下一组"，进入下一个岔路口。
     */
    fun advance() {
        if (currentLegIndex < 3) {
            currentLegIndex++
            currentLegDone = false
        }
    }

    /**
     * 尝试解析一个二维码内容。
     * 从内容里提取数字作为数值，按识别顺序填入当前组的下一个空位（A→B→C）。
     */
    fun parseAndRecord(raw: String): Boolean {
        if (currentLegDone) return false // 当前组已完成，等用户点下一组
        val s = raw.trim()
        val numStr = Regex("\\d+").find(s)?.value ?: return false
        val value = numStr.toIntOrNull() ?: return false

        val leg = legs[currentLegIndex]
        val order = listOf('A', 'B', 'C')
        val nextDir = order.firstOrNull { !leg.codes.containsKey(it) } ?: return false

        leg.codes[nextDir] = value
        totalRead++

        if (leg.isComplete) {
            currentLegDone = true // 完成，停下来等用户确认
        }
        return true
    }

    fun statusText(): String {
        val legNum = currentLegIndex + 1
        val leg = legs[currentLegIndex]
        val sb = StringBuilder()
        sb.append("岔路$legNum/4 · 已读$totalRead/12\n")

        val parts = listOf('A', 'B', 'C').map { dir ->
            val v = leg.codes[dir]
            if (v != null) "$dir:$v" else "$dir:?"
        }
        sb.append(parts.joinToString("  "))

        if (currentLegDone && leg.isComplete) {
            sb.append("\n✓ 推荐走${leg.recommended()}（${leg.recommendedValue()}最小）")
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
                val a = leg.codes['A']
                val b = leg.codes['B']
                val c = leg.codes['C']
                sb.append("A$a B$b C$c → 走${leg.recommended()}")
            } else {
                sb.append("已识别 ${leg.codes.size}/3")
                leg.codes.forEach { (d, v) -> sb.append(" $d:$v") }
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
