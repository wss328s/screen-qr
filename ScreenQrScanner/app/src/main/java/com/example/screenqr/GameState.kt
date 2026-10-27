package com.example.screenqr

/**
 * 比赛导航状态管理：4个岔路口，每个ABC三个码，识别满3个比较出最小方向。
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

    fun reset() {
        legs.forEach { it.codes.clear() }
        currentLegIndex = 0
        totalRead = 0
    }

    /**
     * 尝试解析一个二维码内容。格式：A31 / b12 / C30（字母+数字）
     * 返回 true 表示成功识别并记录。
     */
    fun parseAndRecord(raw: String): Boolean {
        val s = raw.trim()
        val m = Regex("^([ABCabc])\\s*(\\d+)$").find(s) ?: return false
        val dir = m.groupValues[1].uppercaseChar()
        val value = m.groupValues[2].toIntOrNull() ?: return false

        val leg = legs[currentLegIndex]
        // 这个方向已经有值了，不重复记录
        if (leg.codes.containsKey(dir)) return false

        leg.codes[dir] = value
        totalRead++

        // 当前岔路口满3个，自动跳到下一个
        if (leg.isComplete && currentLegIndex < 3) {
            currentLegIndex++
        }
        return true
    }

    /**
     * 生成悬浮窗显示文字。
     */
    fun statusText(): String {
        val legNum = currentLegIndex + 1
        val leg = legs[currentLegIndex]
        val sb = StringBuilder()
        sb.append("岔路$legNum/4 · 已读$totalRead/12\n")

        // 显示当前已识别的码
        val parts = listOf('A', 'B', 'C').map { dir ->
            val v = leg.codes[dir]
            if (v != null) "$dir:$v" else "$dir:?"
        }
        sb.append(parts.joinToString("  "))

        if (leg.isComplete) {
            sb.append("\n推荐走${leg.recommended()}（${leg.recommendedValue()}最小）")
        } else {
            val need = 3 - leg.codes.size
            sb.append("\n还差${need}个码")
        }
        return sb.toString()
    }

    /**
     * 完整结果（主界面用）。
     */
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
