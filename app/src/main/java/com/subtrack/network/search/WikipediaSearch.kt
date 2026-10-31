package com.subtrack.network.search

import com.subtrack.data.backup.asObject
import com.subtrack.data.backup.optional
import com.subtrack.data.backup.required
import com.subtrack.network.items
import com.subtrack.network.number
import com.subtrack.network.parseObject
import com.subtrack.network.text
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** English Wikipedia is a third-party search source; its snippets never verify current prices. */
data class SearchHit(val title: String, val snippet: String, val url: String,
                     val pageUpdatedAt: String?, val retrievedAtMillis: Long,
                     val sourceType: String = "THIRD_PARTY_SEARCH_SUMMARY",
                     val verification: String = "未核验价格")

object WikipediaSearch {
    private const val HOST = "https://en.wikipedia.org/w/api.php"
    private var lastAttemptNanos = 0L
    const val DOCUMENTATION = "https://www.mediawiki.org/wiki/API:Search"

    /** Only user-confirmed query is sent; URL and credentials are not configurable or forwarded. */
    fun requestUrl(query: String): String {
        val clean = query.trim()
        require(clean.length in 1..120 && clean.none { it.isISOControl() }) { "搜索词无效" }
        return "$HOST?action=query&list=search&format=json&utf8=1&srlimit=5&srprop=snippet%7Ctimestamp&srsearch=" +
            URLEncoder.encode(clean, "UTF-8")
    }

    fun decode(response: String, fetchedAtMillis: Long): List<SearchHit> {
        require(response.length <= 131_072 && fetchedAtMillis >= 0)
        val root = parseObject(response)
        require(root.optional("error") == null) { "检索服务返回错误" }
        val rows = root.required("query", "query").asObject("query").items("search")
        require(rows.size <= 5) { "检索结果超过限制" }
        return rows.map { row ->
            val item = row.asObject("search result")
            val id = item.number("pageid") ?: error("缺少来源页面")
            require(id > 0)
            val title = item.text("title")?.takeIf { it.isNotBlank() && it.length <= 300 } ?: error("无效标题")
            val snippet = item.text("snippet") ?: ""
            require(snippet.length <= 4096)
            // Snippets are rendered as plain text only. No source markup or script is executed.
            val plain = snippet.replace(Regex("<[^>]{0,512}>"), " ")
                .replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace(Regex("\\s+"), " ").trim()
            val updated = item.text("timestamp")?.also { Instant.parse(it) }
            SearchHit(title, plain, "https://en.wikipedia.org/?curid=$id", updated, fetchedAtMillis)
        }
    }

    /** Contact is supplied by the operator to comply with Wikimedia's User-Agent policy. */
    suspend fun search(query: String, operatorContact: String, fetchedAtMillis: Long): List<SearchHit> {
        require(operatorContact.length in 5..200 && operatorContact.all { it.code in 33..126 } &&
            (operatorContact.startsWith("https://") || operatorContact.contains('@'))) { "请配置检索服务的联系地址" }
        val url = requestUrl(query)
        synchronized(this) {
            val now = System.nanoTime()
            require(lastAttemptNanos == 0L || now - lastAttemptNanos >= 2_000_000_000L) { "检索过于频繁，请稍后重试" }
            lastAttemptNanos = now
        }
        val bytes = fetch(url, "SubTrack/1.4 (contact: $operatorContact)")
        return decode(bytes.toString(Charsets.UTF_8), fetchedAtMillis)
    }

    private suspend fun fetch(url: String, userAgent: String): ByteArray = withTimeout(25_000) {
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", userAgent)
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { c.disconnect() }
                try {
                    require(c.responseCode in 200..299) { "检索失败 HTTP ${c.responseCode}" }
                    require(c.contentLengthLong <= 131_072) { "检索响应过大" }
                    val out = java.io.ByteArrayOutputStream()
                    c.inputStream.use { stream ->
                        val buffer = ByteArray(4096)
                        while (cont.isActive) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            require(out.size() + n <= 131_072) { "检索响应过大" }
                            out.write(buffer, 0, n)
                        }
                    }
                    if (cont.isActive) cont.resume(out.toByteArray())
                } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
                finally { c.disconnect() }
            }
        }
    }
}
