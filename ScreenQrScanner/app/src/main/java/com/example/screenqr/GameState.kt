package com.example.screenqr

/**
 * 比赛导航：4个岔路，每个3个码，自动连续识别。
 * - 同一个码重置前只记录一次（永久去重）
 * - 满3个自动跳到下一组
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
    // 永久去重：重置前同一码只记录一次
    private val seenCodes = mutableSetOf<String>()

    fun reset() {
        legs.forEach { it.codes.clear() }
        currentLegIndex = 0
        totalRead = 0
        seenCodes.clear()
    }

    fun parseAndRecord(raw: String): Boolean {
        val s = raw.trim()
        val numStr = Regex("\\d+").find(s)?.value ?: return false
        val value = numStr.toIntOrNull() ?: return false

        // 永久去重
        if (seenCodes.contains(s)) return false

        if (currentLegIndex >= 3 && legs[3].isComplete) return false
        val leg = legs[currentLegIndex]
        if (leg.codes.size >= 3) return false

        leg.codes.add(value)
        totalRead++
        seenCodes.add(s)

        if (leg.isComplete && currentLegIndex < 3) {
            currentLegIndex++
        }
        return true
    }

    fun statusText(): String {
        val legNum = currentLegIndex + 1
        val leg = legs[currentLegIndex]
        val sb = StringBuilder()
        sb.append("岔路$legNum/4 · 已读$totalRead/12\n")
        val parts = leg.codes.mapIndexed { i, v -> "码${i+1}:$v" }
        val display = parts.ifEmpty { listOf("码1:?","码2:?","码3:?") }
        sb.append(display.joinToString("  "))
        for (i in 0..3) {
            val l = legs[i]
            if (l.isComplete) {
                sb.append("\n岔路${i+1}: ${l.codes.joinToString(" ")} → 走第${l.recommendedIndex()!!+1}个(${l.recommendedValue()})")
            }
        }
        return sb.toString()
    }

    fun fullSummary(): String {
        val sb = StringBuilder()
        legs.forEachIndexed { i, leg ->
            sb.append("岔路${i+1}: ")
            if (leg.isComplete) {
                sb.append(leg.codes.joinToString(" "))
                sb.append(" → 走第${leg.recommendedIndex()!!+1}个(${leg.recommendedValue()}最小)")
            } else {
                sb.append("已识别 ${leg.codes.size}/3")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    // 悬浮窗列表：当前岔路已识别的码，每行 "• value"
    fun listText(): String =
        legs[currentLegIndex].codes.joinToString("\n") { "• $it" }
}
