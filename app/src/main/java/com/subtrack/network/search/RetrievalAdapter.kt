package com.subtrack.network.search

import com.subtrack.data.backup.asObject
import com.subtrack.data.backup.required
import com.subtrack.domain.analysis.PricePeriod
import com.subtrack.domain.analysis.Provenance
import com.subtrack.domain.analysis.RetrievedDocument
import com.subtrack.domain.analysis.SourceType
import com.subtrack.network.items
import com.subtrack.network.parseObject
import com.subtrack.network.text
import java.net.URLEncoder

/** 用户主动发起的检索条件。只包含完成查询所需字段，不含备注/台账/支付记录。 */
data class PriceQueryRequest(
    val service: String,
    val region: String,
    val plan: String,
    val currency: String,
    val period: PricePeriod,
    val taxTerms: String,
    val discountTerms: String,
    val benefitTerms: String,
    val newUserOnly: Boolean,
) {
    fun validate() {
        require(service.isNotBlank() && service.length <= 40) { "请确认服务" }
        require(region.isNotBlank() && region.length <= 100) { "请确认地区" }
        require(plan.isNotBlank() && plan.length <= 100) { "请确认套餐" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "请确认币种" }
        require(taxTerms.length <= 200 && discountTerms.length <= 200 && benefitTerms.length <= 200)
    }
}

/** 一次检索真正拿到的材料 + 来源标记。 */
data class RetrievalBundle(
    val adapterId: String,
    val query: PriceQueryRequest,
    val documents: List<RetrievedDocument>,
    val statusMessage: String,
    val isFixture: Boolean,
)

open class RetrievalUnavailableException(message: String) : Exception(message)

/**
 * 可配置、可测试的检索适配器。所有实现必须只返回“来源文档”，
 * 抽取与校验在流水线中独立完成；适配器不得直接产生“可信价格”。
 */
interface PriceRetrievalAdapter {
    val id: String
    val isFixture: Boolean

    /** 真实联网请求是否必须用户逐次确认发送范围。 */
    val requiresUserConsent: Boolean

    suspend fun retrieve(query: PriceQueryRequest): RetrievalBundle
}

/**
 * 确定性进程内夹具适配器：**仅供测试**。返回的数据清楚标为夹具，
 * 绝不代表真实或当前官方价格。可通过 [documentsProvider] 注入故障/冲突样例。
 */
class FixturePriceRetrievalAdapter(
    private val retrievedAtMillis: Long = FIXTURE_RETRIEVED_AT,
    private val documentsProvider: ((PriceQueryRequest) -> List<RetrievedDocument>)? = null,
) : PriceRetrievalAdapter {
    override val id: String = "fixture"
    override val isFixture: Boolean = true
    override val requiresUserConsent: Boolean = false

    override suspend fun retrieve(query: PriceQueryRequest): RetrievalBundle {
        query.validate()
        val documents = documentsProvider?.invoke(query) ?: defaultDocuments(query)
        documents.forEach { it.validate() }
        return RetrievalBundle(id, query, documents, "夹具数据（测试用途，非真实价格）", true)
    }

    private fun defaultDocuments(query: PriceQueryRequest): List<RetrievedDocument> {
        val yearly = query.period == PricePeriod.YEARLY
        val amountMinor = if (yearly) 20_000L else 2_000L
        val evidence = if (yearly) "${query.currency} 200.00 per year" else "${query.currency} 20.00 per month"
        val body = buildString {
            append("{\"quotes\":[{\"id\":\"fixture-1\"")
            append(",\"service\":").append(query.service.jsonString())
            append(",\"plan\":").append(query.plan.jsonString())
            append(",\"region\":").append(query.region.jsonString())
            append(",\"currency\":").append(query.currency.jsonString())
            append(",\"amountMinor\":").append(amountMinor)
            append(",\"period\":").append(if (yearly) "\"YEARLY\"" else "\"MONTHLY\"")
            append(",\"taxTerms\":").append(query.taxTerms.jsonString())
            append(",\"discountTerms\":").append(query.discountTerms.jsonString())
            append(",\"benefitTerms\":").append(query.benefitTerms.jsonString())
            append(",\"newUserOnly\":").append(query.newUserOnly)
            append(",\"sourceUrl\":\"https://fixture.subtrack.invalid/price\"")
            append(",\"evidence\":").append(evidence.jsonString())
            append("}]}")
        }
        return listOf(
            RetrievedDocument(
                url = "https://fixture.subtrack.invalid/price",
                body = body,
                sourceType = SourceType.OFFICIAL,
                pageDate = FIXTURE_PAGE_DATE,
                retrievedAtMillis = retrievedAtMillis,
                provenance = Provenance.FIXTURE,
            ),
        )
    }

    companion object {
        const val FIXTURE_RETRIEVED_AT = 1_793_225_600_000L // 2026-10-28 UTC，晚于夹具页面日期
        const val FIXTURE_PAGE_DATE = "2026-10-20"
    }
}

/**
 * 用户自带来源：不联网，只分析用户粘贴的 URL/文本，因此显示“未进行在线核验”。
 * URL 仅用于标识来源与校验协议，不做任何抓取。
 */
class UserProvidedSourceAdapter(
    private val url: String,
    private val text: String,
    private val retrievedAtMillis: Long,
) : PriceRetrievalAdapter {
    override val id: String = "user-source"
    override val isFixture: Boolean = false
    override val requiresUserConsent: Boolean = true

    override suspend fun retrieve(query: PriceQueryRequest): RetrievalBundle {
        query.validate()
        require(url.startsWith("https://") && !url.contains(' ')) { "请填写 HTTPS 来源地址（仅标识，不抓取）" }
        require(text.isNotBlank() && text.length <= RetrievedDocument.MAX_BODY_CHARS) { "请粘贴来源文本" }
        require(retrievedAtMillis > 0)
        val document = RetrievedDocument(url, text, SourceType.THIRD_PARTY, pageDate = null,
            retrievedAtMillis = retrievedAtMillis, provenance = Provenance.USER_PROVIDED)
        document.validate()
        return RetrievalBundle(id, query, listOf(document), "未进行在线核验（用户提供材料）", false)
    }
}

/**
 * Documented search protocol used by [HttpSearchRetrievalAdapter]:
 *
 *  - **`brave`** (Brave Web Search API, https://api.search.brave.com/res/v1/web/search):
 *    request `GET {endpoint}?q=<query>&count=N`, headers `Accept: application/json` and
 *    `X-Subscription-Token: <search key>`. Response: `{"web":{"results":[{"title","url","description","age"}]}}`.
 *
 * The search key is sent ONLY to [endpoint]; result URLs are never fetched with it, redirects are refused, and the key
 * never appears in exceptions/logs.
 */
data class HttpSearchConfig(
    val endpoint: String = "",
    val apiKey: String = "",
    val enabled: Boolean = false,
    val protocol: String = PROTOCOL_BRAVE,
    /** Tests/isolated local fixtures may use http://127.0.0.1; production always requires https. */
    val allowLocalDebug: Boolean = false,
) {
    val configured: Boolean get() = enabled && endpoint.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val PROTOCOL_BRAVE = "brave"
    }
}

/**
 * 真实 HTTP 检索适配器：默认**关闭**且故障受控。
 * - [HttpSearchConfig.enabled] 默认 false；
 * - 未配置 Key 时直接抛 [RetrievalUnavailableException]，绝不发起真实请求；
 * - 联网走 [RestrictedFetcher]（https/SSRF/大小/超时/地址绑定），认证头只随该请求发送；
 * - 返回的是第三方搜索摘要，不是官方现价，后续仍需逐报价证据绑定（模型抽取后由本地校验器裁决）。
 */
class HttpSearchRetrievalAdapter(
    private val config: HttpSearchConfig,
    private val fetcher: RestrictedFetcher,
) : PriceRetrievalAdapter {
    override val id: String = "http-search"
    override val isFixture: Boolean = false
    override val requiresUserConsent: Boolean = true

    override suspend fun retrieve(query: PriceQueryRequest): RetrievalBundle {
        if (!config.enabled) throw RetrievalUnavailableException("在线检索未启用（默认关闭）")
        if (config.apiKey.isBlank()) throw RetrievalUnavailableException("未配置检索服务 Key；不发起真实请求")
        require(config.protocol == HttpSearchConfig.PROTOCOL_BRAVE) { "不支持的检索协议：${config.protocol}" }
        val endpoint = config.endpoint.trim()
        require(endpoint.startsWith("https://") || (config.allowLocalDebug && isLoopback(endpoint))) {
            "检索服务地址必须为 HTTPS"
        }
        query.validate()
        val url = buildBraveUrl(endpoint, query)
        // Auth is scoped to this request only: never to result URLs, never across redirects (3xx is refused).
        val resource = fetcher.fetch(
            url,
            headers = mapOf("X-Subscription-Token" to config.apiKey, "Accept" to "application/json"),
        )
        val documents = decodeSearchResults(resource.body, resource.retrievedAtMillis)
        val status = if (documents.isEmpty()) {
            "在线检索未返回可用来源（空结果，不做任何价格结论）"
        } else {
            "在线检索返回 ${documents.size} 个第三方来源（非官方结论）"
        }
        return RetrievalBundle(id, query, documents, status, false)
    }

    private fun isLoopback(endpoint: String): Boolean =
        runCatching { java.net.URI(endpoint).host in setOf("127.0.0.1", "localhost", "10.0.2.2") }.getOrDefault(false)

    /** Documented Brave Web Search query: `q` + `count`, JSON response. */
    private fun buildBraveUrl(endpoint: String, query: PriceQueryRequest): String {
        val q = listOf(query.service, query.plan, query.region, query.currency, "price",
            if (query.period == PricePeriod.YEARLY) "yearly" else "monthly").joinToString(" ")
        val sep = if (endpoint.contains('?')) '&' else '?'
        return endpoint + sep + "q=" + URLEncoder.encode(q, "UTF-8") + "&count=5"
    }

    private fun decodeSearchResults(body: String, retrievedAtMillis: Long): List<RetrievedDocument> {
        val root = parseObject(body)
        // Brave nests payload under `web.results`; a flat `results` array is accepted for older/self-hosted services.
        val results = runCatching { root.required("web", "search").asObject("web").items("results") }
            .getOrElse { root.items("results") }
        require(results.size <= 20) { "检索结果过多" }
        return results.mapNotNull { value ->
            val item = value.asObject("result")
            val url = item.text("url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val snippet = item.text("description")?.ifBlank { null } ?: item.text("snippet").orEmpty()
            require(snippet.length <= 4096) { "检索摘要过长" }
            val title = item.text("title").orEmpty().take(200)
            val bodyText = if (title.isBlank()) snippet else "$title\n$snippet"
            if (bodyText.isBlank()) return@mapNotNull null
            RetrievedDocument(url, bodyText, SourceType.THIRD_PARTY, null, retrievedAtMillis, Provenance.ONLINE_HTTP)
        }.also { docs -> docs.forEach { it.validate() } }
    }
}

private fun String.jsonString(): String = buildString {
    append('"')
    for (c in this@jsonString) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
