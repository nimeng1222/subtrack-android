package com.subtrack.network

import com.subtrack.data.backup.*
import com.subtrack.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

internal fun parseObject(text: String): JsonValue.Obj {
    require(text.length <= 1_048_576)
    return JsonParser(text).parseDocument().asObject("响应")
}
internal fun JsonValue.Obj.text(key: String): String? = optional(key)?.asString(key)?.also { require(it.length <= 8192) }
internal fun JsonValue.Obj.number(key: String): Long? = optional(key)?.asLong(key)
internal fun JsonValue.Obj.items(key: String): List<JsonValue> = (required(key, key) as? JsonValue.Arr)?.items ?: error("$key 应为数组")

object TemplateCodec {
    fun decode(text: String): List<SubscriptionTemplate> {
        val root = parseObject(text)
        require(root.number("schemaVersion") == 1L) { "模板数据版本不支持" }
        val version = root.text("dataVersion") ?: error("缺少数据版本")
        val items = root.items("templates")
        require(items.size in 1..500)
        return items.map { value ->
            val o = value.asObject("模板")
            val id = o.text("id") ?: error("模板缺少 ID")
            val name = o.text("serviceName") ?: error("模板缺少名称")
            require(id.matches(Regex("[A-Za-z0-9._-]{1,100}")) && name.length in 1..40)
            val currency = o.text("currency")
            require(currency == null || currency.matches(Regex("[A-Z]{3}")))
            val period = o.text("billingPeriod")
            require(period == null || period in BillingCycleType.entries.map { it.name })
            val price = o.number("referencePriceMinor")
            require(price == null || Limits.isValidPriceMinor(price))
            fun link(k: String) = o.text(k)?.also { require(NetworkUrls.external(it)) { "模板链接无效" } }
            SubscriptionTemplate(id, name, o.text("region"), o.text("plan"), currency, period, price,
                o.optional("taxIncluded")?.asBoolean("taxIncluded"), o.text("trialTerms"), link("websiteUrl"),
                link("cancellationUrl"), link("sourceUrl"), o.text("verifiedAt")?.also { LocalDate.parse(it) }, version)
        }.also { require(it.map { t -> t.id }.distinct().size == it.size) { "模板 ID 重复" } }
    }
}

data class FxSnapshot(val date: String, val base: String, val rates: Map<String, Long>, val preciseRates: Map<String, Long>)

/**
 * 在线汇率来源。
 *
 * 以前只认 Frankfurter 一家（欧洲央行工作日参考价，周末不更新），用户看到的是"昨天的价"；
 * 而且只有一个地址，挂了就整页不可用。这里按顺序给出几个**免注册、免 Key** 的公共接口，
 * 刷新时逐个尝试，取第一个成功的；自定义地址仍然优先，且只用自定义那一个。
 */
object FxSources {
    data class Source(val label: String, val url: String)

    val DEFAULT = listOf(
        Source("Frankfurter（欧洲央行参考价）", "https://api.frankfurter.dev/v1/latest?base=CNY"),
        Source("ExchangeRate-API（每日行情）", "https://open.er-api.com/v6/latest/CNY"),
        Source("ExchangeRate-API 旧版（备用）", "https://api.exchangerate-api.com/v4/latest/CNY"),
        Source("Fawaz Ahmed 汇率表（备用）", "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/cny.json"),
    )

    fun labelOf(url: String): String =
        DEFAULT.firstOrNull { it.url == url }?.label ?: "自定义来源"
}

object FxCodec {
    /**
     * 解析"1 基准币 = 多少报价币"的汇率响应，兼容四种常见形状：
     * - Frankfurter / 旧版 ExchangeRate-API：`base` + `date` + `rates`
     * - ExchangeRate-API v6：`base_code` + `time_last_update_unix` + `rates`
     * - Fawaz Ahmed：`date` + `{ "cny": { "usd": … } }`（币种小写）
     *
     * 无论哪种形状，内部都折算成"1 单位该币种 = 多少人民币"的 1e9 定点，与统计侧同口径。
     */
    fun decode(text: String): FxSnapshot {
        val o = parseObject(text)
        val body = o.fields["rates"]?.asObject("rates")
            ?: o.text("base_code")?.lowercase()?.let { o.fields[it]?.asObject(it) }
            ?: o.text("base")?.lowercase()?.let { o.fields[it]?.asObject(it) }
            ?: bodyBase(o)?.lowercase()?.let { o.fields[it]?.asObject(it) }
            ?: error("缺少汇率表")
        val base = (o.text("base") ?: o.text("base_code") ?: bodyBase(o))?.uppercase() ?: error("缺少基准币种")
        require(base.matches(Regex("[A-Z]{3}")))
        val date = o.text("date")
            ?: o.number("time_last_update_unix")?.let {
                java.time.Instant.ofEpochSecond(it).atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString()
            }
            ?: error("缺少数据日期")
        LocalDate.parse(date)
        val quoted = body.fields.mapValues { (code, v) ->
            val raw = (v as? JsonValue.Num)?.raw ?: error("汇率不是数值")
            require(code.uppercase().matches(Regex("[A-Z]{3}"))) { "汇率的币种代码无效" }
            raw.toBigDecimal().also { require(it > BigDecimal.ZERO && it <= BigDecimal("1000000")) }
        }.mapKeys { it.key.uppercase() }.toMutableMap()
        quoted[base] = BigDecimal.ONE
        val cny = quoted["CNY"] ?: error("缺少 CNY 报价，未替换缓存")
        val supported = com.subtrack.domain.stats.CurrencyConverter.SUPPORTED.map { it.first }.toSet()
        val values = quoted.filterKeys { it in supported }.filterKeys { it != "CNY" }
        val preciseRates = values.mapValues { (code, q) ->
            require(code.matches(Regex("[A-Z]{3}")))
            val rate = cny.divide(q, 9, RoundingMode.HALF_UP)
            require(rate > BigDecimal.ZERO && rate <= BigDecimal("10000")) { "派生汇率超出范围" }
            rate.movePointRight(9).longValueExact().also { require(com.subtrack.domain.stats.FxPrecision.valid(it)) }
        }
        require(preciseRates.isNotEmpty())
        // Compatibility view is not used for calculations and is discarded on the next refresh.
        val rates = preciseRates.mapNotNull { (code, n) ->
            val cents = BigDecimal.valueOf(n).movePointLeft(7).setScale(0, RoundingMode.HALF_UP).longValueExact()
            if (Limits.isValidFxRate(cents)) code to cents else null
        }.toMap()
        return FxSnapshot(date, base, rates, preciseRates)
    }

    /** Fawaz 形状没有 base 字段，但键名就是基准币种。 */
    private fun bodyBase(o: JsonValue.Obj): String? =
        listOf("CNY", "USD", "EUR", "GBP", "JPY", "HKD").firstOrNull { o.fields.containsKey(it.lowercase()) }
}

/** 拉一次在线汇率：自定义地址优先，否则按 [FxSources.DEFAULT] 顺序回退。 */
object FxFetch {
    suspend fun fetch(transport: NetworkTransport, customUrl: String): Pair<FxSnapshot, String> {
        val candidates = if (customUrl.isNotBlank()) listOf(customUrl) else FxSources.DEFAULT.map { it.url }
        var failure: Exception? = null
        for (url in candidates) {
            try {
                val bytes = kotlinx.coroutines.withTimeout(12_000) {
                    transport.request(url, throttle = false)
                }
                return FxCodec.decode(bytes.toString(Charsets.UTF_8)) to url
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("没有可用的汇率来源")
    }
}
