package com.example.screenqr

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 活码（网络二维码）解析器。
 *
 * 活码：二维码图案里是一个固定短链接，扫码后由服务端返回"当前内容"，
 * 后台可随时修改内容而二维码图案不变（典型：草料二维码 cli.im）。
 *
 * 支持：
 * - 草料活码：https://qrN.cn/xxxx/yyyy，通过公开的 ?format=md 视图读取，
 *   解析 markdown 中 "## 正文内容" 后的真实正文；
 * - 通用网址/短链：跟随重定向，提取最终网页标题或正文文本。
 *
 * 注意：所有方法都可能发起网络请求，必须在后台线程调用；超时短、有大小上限。
 */
object LiveCodeResolver {

    data class LiveResult(
        val url: String,
        val content: String,
        val isCaoliao: Boolean
    )

    private val webLinkRegex = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val caoliaoHostRegex = Regex("qr[0-9]+\\.cn", RegexOption.IGNORE_CASE)
    private val caoliaoPathRegex = Regex("/[A-Za-z0-9]+/[A-Za-z0-9]+/?")

    /** 文本是否是一个网址（活码候选） */
    fun isLiveCode(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && webLinkRegex.containsMatchIn(t)
    }

    /** 是否为草料活码链接 */
    fun isCaoliao(text: String): Boolean {
        return try {
            val uri = URI(text.trim())
            val host = uri.host ?: return false
            val schemeOk = uri.scheme?.equals("https", ignoreCase = true) == true
            val hostOk = caoliaoHostRegex.matches(host)
            val pathOk = uri.path?.let { caoliaoPathRegex.matches(it) } == true
            schemeOk && hostOk && pathOk && uri.userInfo == null && uri.port == -1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解析活码，返回真实内容（后台线程调用）。
     * @param fresh 为 true 时强制走网络（不使用短期缓存）
     */
    @Synchronized
    fun resolve(text: String, fresh: Boolean = true): LiveResult? {
        val url = text.trim()
        // 短期缓存：同一 URL 在缓存TTL内不重复请求
        val now = System.currentTimeMillis()
        if (!fresh) {
            cache[url]?.let { if (now - it.time < CACHE_TTL_MS) return it.result }
        }
        val caoliao = isCaoliao(url)
        val content = if (caoliao) fetchCaoliao(url) else fetchGeneric(url)
        if (content.isNullOrBlank()) return null
        val r = LiveResult(url, content, caoliao)
        cache[url] = CacheEntry(now, r)
        // 控制缓存大小
        if (cache.size > 32) {
            val it = cache.entries.iterator()
            if (it.hasNext()) it.remove()
        }
        return r
    }

    private data class CacheEntry(val time: Long, val result: LiveResult)
    private val cache = LinkedHashMap<String, CacheEntry>()
    private const val CACHE_TTL_MS = 3000L

    // ---- 草料 ----

    private fun markdownUrl(url: String): String {
        val uri = URI(url)
        // scheme, host, path, query(format=md), fragment
        return URI("https", uri.host, uri.path, "format=md", null).toASCIIString()
    }

    private fun fetchCaoliao(url: String): String? {
        val body = httpGet(markdownUrl(url), accept = "text/markdown") ?: return null
        return parseCaoliaoMarkdown(body)
    }

    /**
     * 解析草料 markdown：定位 "## 正文内容"，取其后到下一个标题之前的内容，
     * 清理图片/链接/navLink/标记，得到可读文本。
     * 若找不到该标题，退化为取正文文本。
     */
    fun parseCaoliaoMarkdown(md: String): String? {
        // 去掉 YAML frontmatter（开头 --- ... ---）
        var raw = md
        if (raw.trimStart().startsWith("---")) {
            val end = raw.trimStart().substring(3).indexOf("---")
            if (end >= 0) {
                val start = raw.indexOf("---")
                raw = raw.substring(start + 3 + end + 3)
            }
        }
        val lines = raw.replace("\r\n", "\n").split("\n")

        // 定位正文
        var idx = lines.indexOfFirst { it.trim() == "## 正文内容" }
        val bodyLines: List<String>
        if (idx >= 0) {
            val seg = ArrayList<String>()
            for (j in idx + 1 until lines.size) {
                val l = lines[j]
                if (l.trimStart().startsWith("#")) break
                seg.add(l)
            }
            bodyLines = seg
        } else {
            bodyLines = lines
        }

        // 先在整个正文块上处理跨行元素（草料的链接/图片常跨多行）
        var block = bodyLines.joinToString("\n")
        block = block.replace(Regex("!\\[[^]]*]\\([^)]*\\)", RegexOption.DOT_MATCHES_ALL), "")
        block = block.replace(Regex("\\[([^]]*)]\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)) { m ->
            m.groupValues[1]
        }
        // 再逐行清理
        val cleaned = block.split("\n").mapNotNull { cleanMdLine(it) }
        var text = cleaned.joinToString("\n").trim()
        if (text.isEmpty()) {
            // 退化：整段里找第一段纯文本
            text = lines.mapNotNull { cleanMdLine(it) }.firstOrNull() ?: ""
        }
        return text.trim().ifEmpty { null }
    }

    /** 清理一行 markdown，返回可读文本；无意义行返回 null */
    private fun cleanMdLine(line: String): String? {
        var s = line.trim()
        if (s.isEmpty()) return null
        // 兜底：行内图片/链接（正常已在块级处理）
        s = s.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        s = s.replace(Regex("\\[([^]]*)]\\(([^)]*)\\)")) { m -> m.groupValues[1].trim() }
        // 跨行链接处理后的孤立碎片
        if (s == "[" || s == "]" || s.startsWith("](")) return null
        // 去掉加粗/斜体/行内代码/引用/列表符号
        s = s.replace("**", "").replace("__", "").replace("`", "")
        s = s.trimStart('>', '-', '*', ' ', ' ').trim()
        s = decodeHtmlEntities(s).replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null
        // 残留的 navLink:xxx 裸串丢弃
        if (s.startsWith("navLink:")) return null
        return s
    }

    // ---- 通用网址 ----

    private fun fetchGeneric(url: String): String? {
        // 跟随重定向，拿最终 HTML
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept", "text/html,text/plain,*/*")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
                )
            }
            if (conn.responseCode !in 200..299) return null
            val ctype = conn.contentType ?: ""
            val input = conn.inputStream ?: return null
            val raw = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // 限制大小
            val html = if (raw.length > 200000) raw.substring(0, 200000) else raw
            return when {
                ctype.startsWith("text/plain") -> html.trim().take(500)
                else -> extractHtmlTitle(html)
            }
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /** 从 HTML 提取 <title>；没有则取去标签后的首段文本 */
    private fun extractHtmlTitle(html: String): String? {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { decodeHtmlEntities(it) }?.trim()
        if (!title.isNullOrBlank()) return title.take(200)
        val text = html.replace(Regex("<script.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<style.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .let { decodeHtmlEntities(it) }
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.take(200).ifEmpty { null }
    }

    private fun decodeHtmlEntities(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    // ---- 通用 HTTP GET ----

    private fun httpGet(urlStr: String, accept: String): String? {
        var conn: HttpsURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", accept)
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
            }
            if (conn.responseCode != 200) return null
            val ct = conn.contentType ?: ""
            if (accept == "text/markdown" && !ct.startsWith("text/markdown")) {
                // 部分服务器不严格返回 content-type，仍尝试解析
            }
            val input = conn.inputStream ?: return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > 131072) return null
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }
}
