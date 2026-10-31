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
    // 永久去重：按「提取出的数值」去重（而非原始字符串）。
    // 活码/死码/两引擎对同一数字码可能返回不同格式（前导零/空格/换行），
    // 按数值去重才能避免同一码因格式差异被重复记录。
    private val seenValues = mutableSetOf<Int>()
    // 正在联网解析的活码数量（loading 提示）
    var liveLoadingCount = 0
        private set
    // 最近一次活码解析失败原因（诊断用，悬浮窗可见；成功或重置后清空）
    @Volatile var lastLiveError: String? = null
        private set

    fun reset() {
        legs.forEach { it.codes.clear() }
        currentLegIndex = 0
        totalRead = 0
        seenValues.clear()
        liveLoadingCount = 0
        lastLiveError = null
    }

    fun setLiveError(msg: String?) { lastLiveError = msg }

    fun beginLive() { liveLoadingCount++ }
    fun endLive() { if (liveLoadingCount > 0) liveLoadingCount-- }

    /** 从文本里提取首个数值（活码解析出的正文/死码原文里的数字） */
    private fun extractValue(s: String): Int? {
        return Regex("\\d+").find(s.trim())?.value?.toIntOrNull()
    }

    fun parseAndRecord(raw: String): Boolean {
        val value = extractValue(raw) ?: return false

        // 永久去重（按数值）
        if (seenValues.contains(value)) return false

        if (currentLegIndex >= 3 && legs[3].isComplete) return false
        val leg = legs[currentLegIndex]
        if (leg.codes.size >= 3) return false

        leg.codes.add(value)
        totalRead++
        seenValues.add(value)

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
        if (liveLoadingCount > 0) {
            sb.append("\n正在获取网络内容…")
        }
        lastLiveError?.let { sb.append("\n⚠ $it") }
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
}
