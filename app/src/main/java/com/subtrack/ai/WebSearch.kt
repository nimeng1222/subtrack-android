package com.subtrack.ai

import com.subtrack.network.NetworkTransport
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * 免 Key 的公开网页检索。
 *
 * AI 助手原来只有 `fetch_page`：模型必须先"猜"出一个网址才能抓。像「Kimi 现在订阅价格是多少」
 * 这种问题，官网首页是脚本渲染的、猜的路径经常 404，几次抓取预算瞬间烧完，最后只能返回
 * "查了 N 个页面还没整理出答案"。这里补上"先搜再抓"的第一步：
 *
 *   search_web（拿到官方域名/帮助中心地址） → fetch_page（打开页面读正文） → 回答
 *
 * 实现只解析搜索引擎自己返回的 HTML（DuckDuckGo Lite / Bing），不引入任何账号或 Key；
 * 返回的摘要**只是找网址的线索**，不能当作价格结论——系统提示与工具描述都明确要求再打开页面。
 *
 * 两个引擎都是纯文本 HTML，解析函数独立出来便于 JVM 单测锁住选择器变化。
 */
internal object WebSearch {

    data class Result(val title: String, val url: String, val snippet: String)

    /** [error] 仅在一条结果都没有时给出，用于让模型知道自己还能不能继续换引擎/换问法。 */
    data class Outcome(val results: List<Result>, val error: String? = null)

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 SubTrack"

    private const val MAX_QUERY_CHARS = 160
    private const val MAX_RESULTS = 8

    private class Engine(
        val name: String,
        val urlTemplate: String,
        val parse: (String) -> List<Result>,
    )

    // 顺序有意义：DuckDuckGo Lite 对中文结果更干净且返回真实目标地址；被风控/空结果时再换 Bing。
    private val engines = listOf(
        Engine("duckduckgo", "https://lite.duckduckgo.com/lite/?q=%s", ::parseDuckDuckGo),
        Engine("bing", "https://www.bing.com/search?q=%s&setlang=zh-Hans", ::parseBing),
    )

    suspend fun search(transport: NetworkTransport, rawQuery: String): Outcome {
        val query = normalizeQuery(rawQuery)
            ?: return Outcome(emptyList(), "搜索词无效（为空或过长）")
        var lastError: String? = null
        for (engine in engines) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val bytes = transport.request(
                    engine.urlTemplate.format(encoded),
                    accept = "text/html,application/xhtml+xml",
                    throttle = false,
                    maxBytes = 1_500_000,
                    userAgent = USER_AGENT,
                )
                val results = engine.parse(bytes.toString(Charsets.UTF_8))
                    .filter { it.url.startsWith("https://") }
                    .distinctBy { it.url }
                    .take(MAX_RESULTS)
                if (results.isNotEmpty()) return Outcome(results)
                lastError = "${engine.name} 没有返回可用结果"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${engine.name}：${e.message?.take(100) ?: "请求失败"}"
            }
        }
        return Outcome(emptyList(), lastError ?: "没有可用的搜索引擎")
    }

    private fun normalizeQuery(raw: String): String? {
        val clean = raw
            .replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty() || clean.length > MAX_QUERY_CHARS) return null
        return clean
    }

    // ── DuckDuckGo Lite ──────────────────────────────────────────────────

    /** Lite 页面结构：`<a class='result-link' href="//duckduckgo.com/l/?uddg=...">标题</a>` + `<td class='result-snippet'>`。 */
    internal fun parseDuckDuckGo(html: String): List<Result> {
        val links = Regex("(?is)<a\\b([^>]*)>(.*?)</a>").findAll(html).mapNotNull { match ->
            val attrs = match.groupValues[1]
            if (!attrs.contains("result-link", ignoreCase = true)) return@mapNotNull null
            val href = Regex("(?i)href\\s*=\\s*\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val target = decodeDuckDuckGoTarget(unescape(href).trim()) ?: return@mapNotNull null
            val title = plain(match.groupValues[2])
            target to title
        }.toList()
        val snippets = Regex(
            "(?is)<td\\b[^>]*class\\s*=\\s*'[^']*result-snippet[^']*'[^>]*>(.*?)</td>",
        ).findAll(html).map { plain(it.groupValues[1]) }.toList()
        return links.mapIndexed { index, (url, title) ->
            Result(title.ifBlank { hostOf(url) }, url, snippets.getOrElse(index) { "" })
        }
    }

    /** DDG 把目标地址放在 `uddg=` 里（`//duckduckgo.com/l/?uddg=https%3A%2F%2F...&rut=...`）。 */
    internal fun decodeDuckDuckGoTarget(raw: String): String? {
        val absolute = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://duckduckgo.com$raw"
            else -> raw
        }
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "duckduckgo.com" && !host.endsWith(".duckduckgo.com")) {
            return absolute.takeIf { it.startsWith("https://") && !isAsset(uri) }
        }
        val target = queryParameters(uri.rawQuery)["uddg"] ?: return null
        val decoded = runCatching { URLDecoder.decode(target, "UTF-8") }.getOrNull() ?: return null
        return decoded.takeIf {
            it.startsWith("https://") && runCatching { !isAsset(URI(it)) }.getOrDefault(false)
        }
    }

    // ── Bing ─────────────────────────────────────────────────────────────

    /** 结果块：`<li class="b_algo">…<h2><a href="https://www.bing.com/ck/a?...u=a1<base64>">…`。 */
    internal fun parseBing(html: String): List<Result> {
        val blocks = Regex(
            "(?is)<li\\b[^>]*class\\s*=\\s*\"[^\"]*\\bb_algo\\b[^\"]*\"[^>]*>(.*?)</li>",
        ).findAll(html)
        return blocks.mapNotNull { block ->
            val body = block.groupValues[1]
            val anchor = Regex(
                "(?is)<h2[^>]*>\\s*<a[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>",
            ).find(body) ?: return@mapNotNull null
            val url = decodeBingTarget(unescape(anchor.groupValues[1]).trim()) ?: return@mapNotNull null
            val title = plain(anchor.groupValues[2]).ifBlank { hostOf(url) }
            val snippet = Regex("(?is)<p[^>]*>(.*?)</p>").find(body)
                ?.let { plain(it.groupValues[1]) }.orEmpty()
            Result(title, url, snippet)
        }.toList()
    }

    /** Bing 用 `https://www.bing.com/ck/a?...&u=a1<base64url>` 包装外链。 */
    internal fun decodeBingTarget(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val direct = raw.startsWith("https://") && !host.endsWith("bing.com") && !isAsset(uri)
        if (direct) return raw
        if (!host.endsWith("bing.com")) return null
        val u = queryParameters(uri.rawQuery)["u"] ?: return null
        if (!u.startsWith("a1")) return null
        val base64 = u.substring(2)
        val padded = base64 + "=".repeat((4 - base64.length % 4) % 4)
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val target = runCatching { URI(decoded) }.getOrNull() ?: return null
        return decoded.takeIf {
            it.startsWith("https://") && !isAsset(target) &&
                target.host?.lowercase()?.endsWith("bing.com") != true
        }
    }

    // ── 公共小工具 ───────────────────────────────────────────────────────

    private fun queryParameters(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()

    private fun isAsset(uri: URI): Boolean {
        val path = uri.path?.lowercase().orEmpty()
        return path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".png") ||
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") ||
            path.endsWith(".svg") || path.endsWith(".ico") || path.endsWith(".woff") ||
            path.endsWith(".woff2")
    }

    private fun plain(raw: String): String = unescape(raw.replace(Regex("(?s)<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(300)

    private fun unescape(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    private fun hostOf(url: String): String =
        runCatching { URI(url).host ?: url }.getOrDefault(url)
}
