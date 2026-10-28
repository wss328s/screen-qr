package com.example.screenqr

/**
 * 纯扫码历史：只存扫到的内容，不做比赛逻辑。
 *
 * 历史记录：同一内容在重置前只保留一次（永久去重）。
 * 反馈去重：同一内容在去重窗口内只提示一次（音效/震动），但历史仍只显示一次。
 *
 * 活码（网络二维码）：扫到的是固定短链，解析后把"后台真实内容"作为结果加入；
 * 后台内容被修改后，新内容与旧内容不同，会作为新结果显示（图案不变也能更新）。
 */
object ScanHistory {

    private val items = mutableListOf<String>()
    private const val MAX_ITEMS = 10

    // 历史里已有的内容（永久去重，直到reset）
    private val historySet = LinkedHashSet<String>()

    // 最近反馈过的内容（按去重窗口过期）
    private val recentNotify = LinkedHashMap<String, Long>()

    // 正在联网解析的活码数量
    private var liveLoadingCount = 0

    @Synchronized
    fun reset() {
        items.clear()
        historySet.clear()
        recentNotify.clear()
        liveLoadingCount = 0
    }

    @Synchronized
    fun beginLive() { liveLoadingCount++ }

    @Synchronized
    fun endLive() { liveLoadingCount = maxOf(0, liveLoadingCount - 1) }

    /**
     * 加到历史：同一内容在重置前只加一次。
     * 返回 true 表示这是新内容（历史里没有）。
     */
    @Synchronized
    fun add(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false

        if (historySet.contains(s)) return false

        historySet.add(s)
        items.add(s)
        if (items.size > MAX_ITEMS) {
            val removed = items.removeAt(0)
            historySet.remove(removed)
        }
        return true
    }

    /**
     * 判断是否应该反馈（音效/震动）。
     * 同一内容在 ttlMs 内只反馈一次。
     * 返回 true 表示应该反馈。
     */
    @Synchronized
    fun shouldNotify(raw: String, ttlMs: Long): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false

        val now = System.currentTimeMillis()
        recentNotify.entries.removeIf { now - it.value > ttlMs }

        if (recentNotify.containsKey(s)) return false

        recentNotify[s] = now
        return true
    }

    @Synchronized
    fun statusText(): String {
        if (items.isEmpty() && liveLoadingCount == 0) return "等待扫码..."
        val sb = StringBuilder()
        items.forEachIndexed { i, content ->
            sb.append("第${i+1}个: $content\n")
        }
        if (liveLoadingCount > 0) {
            sb.append("正在获取网络内容…")
        }
        return sb.toString().trimEnd()
    }

    @Synchronized
    fun fullSummary(): String {
        return items.joinToString("\n")
    }
}
