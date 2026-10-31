package com.example.screenqr

/**
 * 纯扫码历史：只存扫到的内容，不做比赛逻辑。
 *
 * 历史记录：同一内容在重置前只保留一次（永久去重）。
 * 反馈去重：同一内容在去重窗口内只提示一次（音效/震动），但历史仍只显示一次。
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

    // 最近一次活码解析失败原因（诊断用，悬浮窗可见；成功或重置后清空）
    @Volatile private var lastLiveError: String? = null

    fun reset() {
        items.clear()
        historySet.clear()
        recentNotify.clear()
        liveLoadingCount = 0
        lastLiveError = null
    }

    /** 设置/清空活码解析失败提示 */
    fun setLiveError(msg: String?) { lastLiveError = msg }

    @Synchronized
    fun beginLive() { liveLoadingCount++ }

    @Synchronized
    fun endLive() { liveLoadingCount = maxOf(0, liveLoadingCount - 1) }

    /**
     * 加到历史：同一内容在重置前只加一次。
     * 返回 true 表示这是新内容（历史里没有）。
     */
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
    fun shouldNotify(raw: String, ttlMs: Long): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false

        val now = System.currentTimeMillis()
        recentNotify.entries.removeIf { now - it.value > ttlMs }

        if (recentNotify.containsKey(s)) return false

        recentNotify[s] = now
        return true
    }

    fun statusText(): String {
        if (items.isEmpty() && liveLoadingCount == 0 && lastLiveError == null) return "等待扫码..."
        val sb = StringBuilder()
        items.forEachIndexed { i, content ->
            sb.append("第${i+1}个: $content\n")
        }
        if (liveLoadingCount > 0) {
            sb.append("正在获取网络内容…\n")
        }
        lastLiveError?.let { sb.append("⚠ $it") }
        return sb.toString().trimEnd()
    }

    // 已记录数量
    val count: Int get() = items.size

    // 悬浮窗列表：每行 "• 内容"
    fun listText(): String =
        items.joinToString("\n") { "• $it" }

    fun fullSummary(): String {
        return items.joinToString("\n")
    }
}
