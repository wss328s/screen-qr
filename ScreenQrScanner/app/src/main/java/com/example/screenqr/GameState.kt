package com.example.screenqr

/**
 * 比赛导航：4个岔路，每个3个码，自动连续识别。
 * - 同一个码2秒内不重复记录，换了新码立刻识别
 * - 满3个自动跳到下一组，不停顿
 * - 悬浮窗显示当前进度+已完成的结果
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

    // 去重：记录最近识别的内容和时间
    private val recentCodes = LinkedHashMap<String, Long>()
    private val dedupTtl = 3000L

    fun reset() {
        legs.forEach { it.codes.clear() }
        currentLegIndex = 0
        totalRead = 0
        recentCodes.clear()
    }

    /**
     * 从内容里提取数字，按顺序记录。
     * 同一个内容2秒内不重复记，新内容立刻记。
     * 满3个自动跳到下一组。
     */
    fun parseAndRecord(raw: String): Boolean {
        val s = raw.trim()
        val numStr = Regex("\\d+").find(s)?.value ?: return false
        val value = numStr.toIntOrNull() ?: return false

        // 去重清理
        val now = System.currentTimeMillis()
        recentCodes.entries.removeIf { now - it.value > dedupTtl }
        // 同一个内容2秒内不重复
        if (recentCodes.containsKey(s)) return false

        // 4个岔路都扫完了
        if (currentLegIndex >= 3 && legs[3].isComplete) return false

        val leg = legs[currentLegIndex]
        if (leg.codes.size >= 3) return false

        leg.codes.add(value)
        totalRead++
        recentCodes[s] = now

        // 满3个自动跳到下一组
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

        // 显示所有已完成的岔路结果
        for (i in 0..3) {
            val l = legs[i]
            if (l.isComplete) {
                sb.append("\n岔路${i+1}→走第${l.recommendedIndex()!!+1}个(${l.recommendedValue()})")
            }
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
