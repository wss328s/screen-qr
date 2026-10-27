package com.example.screenqr

/**
 * 扫码历史：记录扫到的所有内容，简单列表。
 */
object ScanHistory {

    val items = mutableListOf<String>()
    private val recentCodes = LinkedHashMap<String, Long>()
    private val dedupTtl = 5000L

    fun reset() {
        items.clear()
        recentCodes.clear()
    }

    fun add(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false

        // 去重：同一个内容5秒内不重复
        val now = System.currentTimeMillis()
        recentCodes.entries.removeIf { now - it.value > dedupTtl }
        if (recentCodes.containsKey(s)) return false

        items.add(s)
        recentCodes[s] = now
        return true
    }

    fun statusText(): String {
        if (items.isEmpty()) return "等待扫码..."
        val sb = StringBuilder()
        items.takeLast(10).forEachIndexed { i, content ->
            sb.append("第${items.size - items.takeLast(10).size + i + 1}个: $content\n")
        }
        return sb.toString().trimEnd()
    }

    fun fullSummary(): String {
        return items.mapIndexed { i, content -> "第${i+1}个: $content" }.joinToString("\n")
    }
}
