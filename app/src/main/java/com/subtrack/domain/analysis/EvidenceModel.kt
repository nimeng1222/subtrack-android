package com.subtrack.domain.analysis

import com.subtrack.domain.model.Limits
import java.time.LocalDate

/**
 * N5 可追溯价格模型的“可信层”。
 *
 * 设计原则：检索返回的原始文档（[RetrievedDocument]）才是证据；候选报价
 * （[PriceQuoteCandidate]）来自检索/模型，始终视为**不可信**。只有经过
 * [StructuredPriceValidator] 逐字段绑定到某一份真实返回文档后，才会变成
 * [StructuredPriceQuote]。
 *
 * 模型不能自证“官方现价”或“已核验”：[VerificationStatus] 由本地根据文档来源
 * （provenance / sourceType / 页面日期）计算，候选里即使声称 OFFICIAL 也一律忽略。
 */

enum class SourceType { OFFICIAL, THIRD_PARTY, HISTORICAL, ESTIMATE }

/** 文档从哪里来：夹具、用户粘贴、真实在线抓取。决定“是否在线核验”。 */
enum class Provenance { FIXTURE, USER_PROVIDED, ONLINE_HTTP }

enum class VerificationStatus {
    /** 真实在线抓取 + 官方来源 + 证据绑定。仍是检索快照，以来源页为准。 */
    VERIFIED_OFFICIAL,
    /** 真实在线抓取的第三方/历史/估计来源，已绑定证据但不是官方结论。 */
    THIRD_PARTY_RETRIEVED,
    /** 用户提供材料，未进行在线核验。 */
    USER_MATERIAL_UNVERIFIED,
    /** 确定性测试夹具，绝不代表真实或当前价格。 */
    FIXTURE_ONLY,
    /** 页面日期超过保留期，视为过期。 */
    EXPIRED,
    /** 同一服务/地区/套餐/周期出现多个互相矛盾的价格。 */
    CONFLICT,
}

fun VerificationStatus.zhLabel(): String = when (this) {
    VerificationStatus.VERIFIED_OFFICIAL -> "已绑定证据：官方来源（检索快照，以来源页为准）"
    VerificationStatus.THIRD_PARTY_RETRIEVED -> "第三方来源：已绑定证据，非官方结论"
    VerificationStatus.USER_MATERIAL_UNVERIFIED -> "未进行在线核验（用户提供材料）"
    VerificationStatus.FIXTURE_ONLY -> "测试夹具，非真实价格"
    VerificationStatus.EXPIRED -> "已过期，仅供参考"
    VerificationStatus.CONFLICT -> "来源冲突，禁止直接采用"
}

enum class PriceIssueType {
    INVALID_FIELD,
    FABRICATED_SOURCE,
    FABRICATED_EVIDENCE,
    CURRENCY_CONFUSION,
    PERIOD_CONFUSION,
    AMOUNT_MISMATCH,
    INVALID_TIME,
    EXPIRED,
    CONFLICT,
    PARSE_FAILED,
}

data class PriceIssue(val type: PriceIssueType, val quoteId: String?, val detail: String)

/** 一次检索真正拿到的原始材料。body 是被抓取/粘贴的原文。 */
data class RetrievedDocument(
    val url: String,
    val body: String,
    val sourceType: SourceType,
    val pageDate: String?,
    val retrievedAtMillis: Long,
    val provenance: Provenance,
) {
    fun validate() {
        require(url.startsWith("https://") || url.startsWith("http://")) { "来源地址协议无效" }
        require(body.isNotEmpty() && body.length <= MAX_BODY_CHARS) { "来源内容为空或过大" }
        require(retrievedAtMillis > 0) { "缺少实际检索时间" }
        pageDate?.let { require(runCatching { LocalDate.parse(it) }.isSuccess) { "页面日期格式无效" } }
    }

    companion object { const val MAX_BODY_CHARS = 200_000 }
}

/** 检索/模型抽取出的候选报价（不可信）。所有字段都可能被伪造，必须本地校验。 */
data class PriceQuoteCandidate(
    val quoteId: String,
    val service: String,
    val plan: String,
    val region: String,
    val currency: String,
    val amountMinor: Long,
    val period: PricePeriod,
    val taxTerms: String,
    val discountTerms: String,
    val benefitTerms: String,
    val newUserOnly: Boolean,
    val sourceUrl: String,
    val evidenceText: String,
    val pageDate: String?,
    val retrievedAtMillis: Long,
)

/** 通过本地校验、证据已绑定到具体文档的报价。 */
data class StructuredPriceQuote(
    val quoteId: String,
    val service: String,
    val plan: String,
    val region: String,
    val currency: String,
    val amountMinor: Long,
    val period: PricePeriod,
    val taxTerms: String,
    val discountTerms: String,
    val benefitTerms: String,
    val newUserOnly: Boolean,
    val sourceUrl: String,
    val sourceType: SourceType,
    val pageDate: String?,
    val retrievedAtMillis: Long,
    val evidenceText: String,
    val verification: VerificationStatus,
    val notes: List<String> = emptyList(),
)

sealed interface QuoteValidation {
    data class Accepted(val quote: StructuredPriceQuote) : QuoteValidation
    data class Rejected(val quoteId: String, val reasons: List<PriceIssue>) : QuoteValidation
}

/**
 * 本地校验器：把候选报价逐字段绑定到真实返回文档。
 * 任何一项不成立即拒绝，不“部分采用”。模型声称的来源/核验状态一律不采信。
 */
object StructuredPriceValidator {
    const val EVIDENCE_MAX_CHARS = 4096
    const val EXPIRY_DAYS = 180L

    fun validate(
        candidate: PriceQuoteCandidate,
        documents: List<RetrievedDocument>,
        nowMillis: Long,
    ): QuoteValidation {
        val structural = mutableListOf<PriceIssue>()
        runCatching {
            require(candidate.quoteId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "quoteId 无效" }
            require(candidate.service.isNotBlank() && candidate.service.length <= 40) { "服务无效" }
            require(candidate.plan.isNotBlank() && candidate.plan.length <= 100) { "套餐无效" }
            require(candidate.region.isNotBlank() && candidate.region.length <= 100) { "地区无效" }
            require(candidate.currency.matches(Regex("[A-Z]{3}"))) { "币种无效" }
            require(Limits.isValidPriceMinor(candidate.amountMinor)) { "金额超出范围" }
            require(
                listOf(candidate.taxTerms, candidate.discountTerms, candidate.benefitTerms).all { it.length <= 200 },
            ) { "条件字段过长" }
        }.onFailure {
            structural.add(PriceIssue(PriceIssueType.INVALID_FIELD, candidate.quoteId, "字段无效：${it.message}"))
            return QuoteValidation.Rejected(candidate.quoteId, structural)
        }

        if (candidate.evidenceText.isBlank() || candidate.evidenceText.length > EVIDENCE_MAX_CHARS) {
            return reject(candidate, PriceIssueType.FABRICATED_EVIDENCE, "缺少证据原文或证据过长")
        }

        // 1) 来源必须精确对应某一份真实返回文档，否则视为编造来源。
        val document = documents.firstOrNull { it.url == candidate.sourceUrl }
            ?: return reject(candidate, PriceIssueType.FABRICATED_SOURCE, "来源地址不在本次检索返回结果中")

        // 2) 证据必须是原文的精确子串（网页提示注入也只是“数据”，不会被当作指令）。
        if (!document.body.contains(candidate.evidenceText)) {
            return reject(candidate, PriceIssueType.FABRICATED_EVIDENCE, "证据原文未出现在返回文档中")
        }

        // 3) 金额/币种/周期必须能由证据原文确定性解析出来。
        when (val money = MoneyEvidence.parse(candidate.evidenceText)) {
            is MoneyEvidence.Result.Ambiguous -> return reject(candidate, PriceIssueType.PERIOD_CONFUSION, money.detail)
            is MoneyEvidence.Result.None -> return reject(candidate, PriceIssueType.AMOUNT_MISMATCH, "证据中未找到币种+金额")
            is MoneyEvidence.Result.Parsed -> {
                if (money.currency != candidate.currency) {
                    return reject(candidate, PriceIssueType.CURRENCY_CONFUSION,
                        "证据币种 ${money.currency} 与报价币种 ${candidate.currency} 不一致")
                }
                if (money.amountMinor != candidate.amountMinor) {
                    return reject(candidate, PriceIssueType.AMOUNT_MISMATCH,
                        "证据金额 ${money.amountMinor} 分与报价 ${candidate.amountMinor} 分不一致")
                }
                val periodMatch = if (candidate.period == PricePeriod.MONTHLY) money.hasMonth else money.hasYear
                val periodOpposite = if (candidate.period == PricePeriod.MONTHLY) money.hasYear else money.hasMonth
                if (!periodMatch) {
                    return reject(candidate, PriceIssueType.PERIOD_CONFUSION, "证据周期与报价周期不一致")
                }
                if (periodOpposite) {
                    return reject(candidate, PriceIssueType.PERIOD_CONFUSION, "证据同时出现月付/年付，周期歧义")
                }
            }
        }

        // 4) 实际检索时间必须与文档一致，且不能为零/未来伪造。
        if (candidate.retrievedAtMillis <= 0 || candidate.retrievedAtMillis != document.retrievedAtMillis) {
            return reject(candidate, PriceIssueType.INVALID_TIME, "检索时间与来源文档不一致")
        }

        // 5) 页面日期：文档有则以文档为准；候选自填必须与文档一致，且不能晚于检索日期。
        val pageDate = document.pageDate ?: candidate.pageDate
        if (document.pageDate != null && candidate.pageDate != null && candidate.pageDate != document.pageDate) {
            return reject(candidate, PriceIssueType.INVALID_FIELD, "页面日期与来源文档不一致")
        }
        if (pageDate != null) {
            val parsed = runCatching { LocalDate.parse(pageDate) }.getOrElse {
                return reject(candidate, PriceIssueType.INVALID_FIELD, "页面日期格式无效")
            }
            if (parsed.toEpochDay() > document.retrievedAtMillis / 86_400_000L) {
                return reject(candidate, PriceIssueType.INVALID_FIELD, "页面日期晚于检索时间")
            }
        }

        val verification = verificationFor(document, pageDate, nowMillis)
        val notes = buildList {
            if (pageDate == null) add("来源页未提供日期")
            if (document.provenance == Provenance.FIXTURE) add("夹具数据，仅供测试，不代表真实价格")
            if (document.provenance == Provenance.USER_PROVIDED) add("用户提供材料，未进行在线核验")
        }
        return QuoteValidation.Accepted(
            StructuredPriceQuote(
                quoteId = candidate.quoteId, service = candidate.service, plan = candidate.plan,
                region = candidate.region, currency = candidate.currency, amountMinor = candidate.amountMinor,
                period = candidate.period, taxTerms = candidate.taxTerms, discountTerms = candidate.discountTerms,
                benefitTerms = candidate.benefitTerms, newUserOnly = candidate.newUserOnly,
                sourceUrl = candidate.sourceUrl, sourceType = document.sourceType,
                pageDate = pageDate, retrievedAtMillis = document.retrievedAtMillis,
                evidenceText = candidate.evidenceText, verification = verification, notes = notes,
            ),
        )
    }

    private fun reject(candidate: PriceQuoteCandidate, type: PriceIssueType, detail: String): QuoteValidation =
        QuoteValidation.Rejected(candidate.quoteId, listOf(PriceIssue(type, candidate.quoteId, detail)))

    fun verificationFor(document: RetrievedDocument, pageDate: String?, nowMillis: Long): VerificationStatus {
        val expired = pageDate?.let {
            LocalDate.parse(it).isBefore(LocalDate.ofEpochDay(nowMillis / 86_400_000L).minusDays(EXPIRY_DAYS))
        } ?: false
        if (expired) return VerificationStatus.EXPIRED
        return when (document.provenance) {
            Provenance.FIXTURE -> VerificationStatus.FIXTURE_ONLY
            Provenance.USER_PROVIDED -> VerificationStatus.USER_MATERIAL_UNVERIFIED
            Provenance.ONLINE_HTTP -> when (document.sourceType) {
                SourceType.OFFICIAL -> VerificationStatus.VERIFIED_OFFICIAL
                else -> VerificationStatus.THIRD_PARTY_RETRIEVED
            }
        }
    }
}

/**
 * 从证据原文里确定性解析“币种 + 金额 + 月/年”。绝不借模型心算金额。
 */
object MoneyEvidence {
    sealed interface Result {
        data class Parsed(val currency: String, val amountMinor: Long, val hasMonth: Boolean, val hasYear: Boolean) : Result
        data object None : Result
        data class Ambiguous(val detail: String) : Result
    }

    private val MONEY = Regex("([A-Z]{3})\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val MONTH = Regex("\\b(month|monthly|per\\s+month|/\\s*month)\\b|每月|月付|月费|/月", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("\\b(year|yearly|annual|annually|per\\s+year|/\\s*year)\\b|每年|年付|年费|/年", RegexOption.IGNORE_CASE)

    fun parse(text: String): Result {
        val tokens = MONEY.findAll(text).mapNotNull { m ->
            val minor = runCatching {
                m.groupValues[2].replace(",", "").toBigDecimal().movePointRight(2).longValueExact()
            }.getOrNull() ?: return@mapNotNull null
            m.groupValues[1] to minor
        }.toList()
        if (tokens.isEmpty()) return Result.None
        val currencies = tokens.map { it.first }.distinct()
        if (currencies.size > 1) {
            return Result.Ambiguous("证据出现多个币种：${currencies.joinToString("、")}")
        }
        val amounts = tokens.map { it.second }.distinct()
        if (amounts.size > 1) {
            return Result.Ambiguous("证据出现多个金额：${amounts.joinToString("、")}")
        }
        return Result.Parsed(
            currency = currencies.single(),
            amountMinor = amounts.single(),
            hasMonth = MONTH.containsMatchIn(text),
            hasYear = YEAR.containsMatchIn(text),
        )
    }

    /** 仅用于把“20.00”这类文本解析为分；测试也可直接使用。 */
    fun minorOf(raw: String): Long? = runCatching {
        raw.replace(",", "").toBigDecimal().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()
}

/** 同服务/地区/套餐/周期/币种出现多个不同金额时，全部标记冲突并显式报出。 */
object PriceQuoteSetAnalyzer {
    fun applyConflicts(quotes: List<StructuredPriceQuote>): Pair<List<StructuredPriceQuote>, List<PriceIssue>> {
        val key: (StructuredPriceQuote) -> String = {
            listOf(it.service.lowercase(), it.region.lowercase(), it.plan.lowercase(), it.period.name, it.currency).joinToString("|")
        }
        val groups = quotes.groupBy(key)
        val issues = LinkedHashMap<String, PriceIssue>()
        val adjusted = quotes.map { quote ->
            val group = groups.getValue(key(quote))
            val amounts = group.map { it.amountMinor }.distinct().sorted()
            if (amounts.size > 1) {
                val detail = "同一服务/地区/套餐/周期存在多个价格：${amounts.joinToString("、")} ${quote.currency} 分"
                issues.putIfAbsent("$key|$detail", PriceIssue(PriceIssueType.CONFLICT, quote.quoteId, detail))
                quote.copy(verification = VerificationStatus.CONFLICT)
            } else quote
        }
        return adjusted to issues.values.toList()
    }
}
