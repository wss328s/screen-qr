package com.example.screenqr

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

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

    private const val TAG = "LiveCodeResolver"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    data class LiveResult(
        val url: String,
        val content: String,
        val isCaoliao: Boolean
    )

    /** 最近一次解析的状态/失败原因（供设置页排查活码解析失败） */
    @Volatile
    var lastStatus: String = "未请求"
        private set

    /** 最近一次请求的明细（请求URL / 响应码等） */
    @Volatile
    var lastDetail: String = ""
        private set

    private val webLinkRegex = Regex("^https?://", RegexOption.IGNORE_CASE)
    // 草料域名：qrNN.cn（含任意子域名），如 qr61.cn / www.qr61.cn
    private val caoliaoHostRegex = Regex("([A-Za-z0-9-]+\\.)*qr[0-9]+\\.cn", RegexOption.IGNORE_CASE)
    // 草料路径：1~3 段字母数字（不同活码格式段数不同）
    private val caoliaoPathRegex = Regex("/[A-Za-z0-9]+(/[A-Za-z0-9]+){0,2}/?")
    // 宽松域名判断：只看 host 是否草料（不校验路径/端口），用于 markdown 端点兜底
    private val caoliaoHostLooseRegex = Regex("^([A-Za-z0-9-]+\\.)*qr[0-9]+\\.cn$", RegexOption.IGNORE_CASE)
    // 从文本中提取 URL：某些引擎可能给 URL 加 HRI 前缀（如 "]Q1https://..."），
    // 需要截取从 http(s):// 开始的完整 URL 部分。
    private val urlExtractRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

    /** 文本是否是一个网址（活码候选） */
    fun isLiveCode(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && webLinkRegex.containsMatchIn(t)
    }

    /** 从原始文本中提取干净的 URL（去掉可能的 HRI 前缀等） */
    fun extractUrl(text: String): String {
        val m = urlExtractRegex.find(text.trim())
        var u = m?.value ?: text.trim()
        // 去掉常见包裹/标点污染（HRI 前缀后的引号、括号、逗号句号等）
        u = u.trimEnd(')', ']', '}', '>', ',', ';', ':', '.', '\'', '"', '。', '，', '、')
        return u
    }

    /** 是否为草料活码链接 */
    fun isCaoliao(text: String): Boolean {
        return try {
            val uri = URI(extractUrl(text))
            val host = uri.host ?: return false
            val schemeOk = uri.scheme?.equals("https", ignoreCase = true) == true
            val hostOk = caoliaoHostRegex.matches(host)
            val pathOk = uri.path?.let { caoliaoPathRegex.matches(it) } == true
            schemeOk && hostOk && pathOk && uri.userInfo == null && uri.port == -1
        } catch (e: Exception) {
            false
        }
    }

    /** 宽松判断：host 是否为草料域名（qrNN.cn，含子域名），不校验路径 */
    fun isCaoliaoHost(text: String): Boolean {
        return try {
            val uri = URI(extractUrl(text))
            val host = uri.host ?: return false
            caoliaoHostLooseRegex.matches(host)
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
        val url = extractUrl(text)
        lastStatus = "开始解析: $url"
        // 短期缓存：同一 URL 在缓存TTL内不重复请求
        val now = System.currentTimeMillis()
        if (!fresh) {
            cache[url]?.let { if (now - it.time < CACHE_TTL_MS) return it.result }
        }
        // 关键：不再依赖 isCaoliao 判断（真机扫出的 URL 可能带污染字符导致判断失败）。
        // 草料活码最可靠路径是 ?format=md 端点（直接返回正文文本），所以**先试 markdown**，
        // 失败（非草料/404）再回退通用网页抓取。
        var content = fetchCaoliao(url)
        var caoliao = content != null
        if (content.isNullOrBlank()) {
            content = fetchGeneric(url)
        }
        if (content.isNullOrBlank()) {
            android.util.Log.w(TAG, "resolve fail url=$url status=$lastStatus detail=$lastDetail")
            return null
        }
        val r = LiveResult(url, content, caoliao)
        cache[url] = CacheEntry(now, r)
        // 控制缓存大小
        if (cache.size > 32) {
            val it = cache.entries.iterator()
            if (it.hasNext()) it.remove()
        }
        lastStatus = "解析成功: $content"
        android.util.Log.i(TAG, "resolve ok url=$url content=$content")
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
        // 失败自动重试一次
        val first = fetchGenericOnce(url)
        if (first != null) return first
        return fetchGenericOnce(url)
    }

    private fun fetchGenericOnce(url: String): String? {
        // 手动跟随重定向（最多5跳），兼容 Android 默认不跟随的 HTTPS->HTTP 降级跳转
        var current = url
        for (hop in 0 until 5) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "text/html,text/plain,*/*")
                    setRequestProperty("User-Agent", UA)
                }
                val code = conn.responseCode
                lastDetail = "GET $current -> HTTP $code (hop=$hop)"
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) { lastStatus = "重定向缺少Location(HTTP $code)"; return null }
                    current = URL(URL(current), loc).toString()
                    continue
                }
                if (code !in 200..299) { lastStatus = "HTTP $code"; return null }
                val ctype = conn.contentType ?: ""
                val input = conn.inputStream ?: run { lastStatus = "响应体为空"; return null }
                val raw = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                // 限制大小
                val html = if (raw.length > 200000) raw.substring(0, 200000) else raw
                val result = when {
                    ctype.startsWith("text/plain") -> html.trim().take(500)
                    else -> extractHtmlTitle(html)
                }
                lastStatus = if (result.isNullOrBlank()) "未提取到内容(ctype=$ctype)" else "解析成功(${result.length}字符)"
                return result
            } catch (e: Exception) {
                lastStatus = "异常: ${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.w(TAG, "fetchGeneric fail url=$current : $lastStatus")
                return null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        lastStatus = "重定向过多"
        return null
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
        // 失败自动重试一次（现场网络抖动/超时是解析失败的主因）
        val first = httpGetOnce(urlStr, accept)
        if (first != null) return first
        return httpGetOnce(urlStr, accept)
    }

    private fun httpGetOnce(urlStr: String, accept: String): String? {
        // 手动跟随重定向（最多5跳），兼容 Android 默认不跟随的 HTTPS->HTTP 降级跳转
        var current = urlStr
        for (hop in 0 until 5) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    // 现场手机网络较慢，超时从 2s 提到 6s，避免误判失败
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", accept)
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                    setRequestProperty("Pragma", "no-cache")
                    // 部分 CDN/服务会拦截无 UA 请求
                    setRequestProperty("User-Agent", UA)
                }
                val code = conn.responseCode
                lastDetail = "GET $current -> HTTP $code (hop=$hop)"
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) { lastStatus = "重定向缺少Location(HTTP $code)"; return null }
                    current = URL(URL(current), loc).toString()
                    continue
                }
                if (code !in 200..299) { lastStatus = "HTTP $code"; return null }
                val input = conn.inputStream ?: run { lastStatus = "响应体为空"; return null }
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 131072) { lastStatus = "响应过大(>$total)"; return null }
                    out.write(buf, 0, n)
                }
                val body = String(out.toByteArray(), Charsets.UTF_8)
                lastStatus = "HTTP $code 成功(${body.length}字节)"
                return body
            } catch (e: Exception) {
                lastStatus = "异常: ${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.w(TAG, "httpGet fail url=$current : $lastStatus")
                return null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        lastStatus = "重定向过多"
        return null
    }
}
