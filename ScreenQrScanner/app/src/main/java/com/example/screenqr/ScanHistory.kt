package com.example.screenqr

/**
 * 纯扫码历史：只存扫到的内容，不做比赛逻辑。
 */
object ScanHistory {

    private val items = mutableListOf<String>()
    private const val MAX_ITEMS = 10
    private val recentCodes = LinkedHashMap<String, Long>()
    private const val DEDUP_TTL = 5000L

    fun reset() {
        items.clear()
        recentCodes.clear()
    }

    fun add(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false

        val now = System.currentTimeMillis()
        recentCodes.entries.removeIf { now - it.value > DEDUP_TTL }
        if (recentCodes.containsKey(s)) return false

        items.add(s)
        if (items.size > MAX_ITEMS) items.removeAt(0)
        recentCodes[s] = now
        return true
    }

    fun statusText(): String {
        if (items.isEmpty()) return "等待扫码..."
        val sb = StringBuilder()
        items.forEachIndexed { i, content ->
            sb.append("第${i+1}个: $content\n")
        }
        return sb.toString().trimEnd()
    }

    fun fullSummary(): String {
        return items.joinToString("\n")
    }
}
