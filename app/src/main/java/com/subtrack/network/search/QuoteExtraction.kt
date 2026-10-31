package com.subtrack.network.search

import com.subtrack.data.backup.asBoolean
import com.subtrack.data.backup.asObject
import com.subtrack.data.backup.optional
import com.subtrack.domain.analysis.PriceIssue
import com.subtrack.domain.analysis.PriceIssueType
import com.subtrack.domain.analysis.PriceQuoteCandidate
import com.subtrack.domain.analysis.PriceQuoteSetAnalyzer
import com.subtrack.domain.analysis.PricePeriod
import com.subtrack.domain.analysis.Provenance
import com.subtrack.domain.analysis.QuoteValidation
import com.subtrack.domain.analysis.RetrievedDocument
import com.subtrack.domain.analysis.StructuredPriceQuote
import com.subtrack.domain.analysis.StructuredPriceValidator
import com.subtrack.domain.analysis.VerificationStatus
import com.subtrack.network.items
import com.subtrack.network.number
import com.subtrack.network.parseObject
import com.subtrack.network.text

/** 抽取器只允许读取“本次检索真正返回的材料”，不得凭空生成引用或现价。 */
data class ExtractResult(val candidates: List<PriceQuoteCandidate>, val issues: List<PriceIssue>)

interface QuoteExtractor {
    /** 抽取可以联网（模型抽取），因此是 suspend；实现只能读取本次检索返回的 [documents]。 */
    suspend fun extract(query: PriceQueryRequest, documents: List<RetrievedDocument>): ExtractResult
}

/** 结构化 JSON 文档（夹具或用户粘贴的逐报价 JSON）→ 候选报价。 */
object JsonStructuredQuoteExtractor : QuoteExtractor {
    override suspend fun extract(query: PriceQueryRequest, documents: List<RetrievedDocument>): ExtractResult {
        val candidates = mutableListOf<PriceQuoteCandidate>()
        val issues = mutableListOf<PriceIssue>()
        documents.forEach { document ->
            val trimmed = document.body.trimStart()
            if (!trimmed.startsWith("{")) return@forEach
            try {
                val root = parseObject(document.body)
                val quotes = root.items("quotes")
                require(quotes.size in 1..100) { "quotes 数量无效" }
                quotes.forEach { value ->
                    val o = value.asObject("quote")
                    val id = o.text("id") ?: error("缺少 quote id")
                    val period = when (o.text("period")) {
                        "MONTHLY" -> PricePeriod.MONTHLY
                        "YEARLY" -> PricePeriod.YEARLY
                        else -> error("周期无效")
                    }
                    val amount = o.number("amountMinor") ?: error("缺少金额")
                    candidates += PriceQuoteCandidate(
                        quoteId = id,
                        service = o.text("service") ?: "",
                        plan = o.text("plan") ?: "",
                        region = o.text("region") ?: "",
                        currency = o.text("currency") ?: "",
                        amountMinor = amount,
                        period = period,
                        taxTerms = o.text("taxTerms") ?: "",
                        discountTerms = o.text("discountTerms") ?: "",
                        benefitTerms = o.text("benefitTerms") ?: "",
                        newUserOnly = o.optional("newUserOnly")?.asBoolean("newUserOnly") ?: false,
                        sourceUrl = o.text("sourceUrl") ?: document.url,
                        evidenceText = o.text("evidence") ?: "",
                        pageDate = o.text("pageDate")?.takeIf { it.isNotBlank() } ?: document.pageDate,
                        retrievedAtMillis = document.retrievedAtMillis,
                    )
                }
            } catch (e: Exception) {
                issues += PriceIssue(PriceIssueType.PARSE_FAILED, null, "文档解析失败：${e.message?.take(120)}")
            }
        }
        return ExtractResult(candidates, issues)
    }
}

/**
 * 用户粘贴的自由文本 → 至多一条候选：只认“CUR 金额 per month/year”这种唯一、可核对写法。
 * 多币种/多金额或无法定位就报解析失败，绝不猜测或编造价格。
 */
object UserTextQuoteExtractor : QuoteExtractor {
    private val MONEY_PERIOD = Regex(
        "([A-Z]{3})\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:per\\s+|/\\s*)(month|year)\\b",
        RegexOption.IGNORE_CASE,
    )

    override suspend fun extract(query: PriceQueryRequest, documents: List<RetrievedDocument>): ExtractResult {
        val candidates = mutableListOf<PriceQuoteCandidate>()
        val issues = mutableListOf<PriceIssue>()
        documents.filter { it.provenance == Provenance.USER_PROVIDED && !it.body.trimStart().startsWith("{") }
            .forEach { document ->
                val matches = MONEY_PERIOD.findAll(document.body).toList()
                if (matches.size != 1) {
                    issues += PriceIssue(PriceIssueType.PARSE_FAILED, null,
                        "用户材料中未找到唯一“币种 金额 per 月/年”写法（实际 ${matches.size} 处）")
                    return@forEach
                }
                val match = matches.single()
                val currency = match.groupValues[1].uppercase()
                val amount = com.subtrack.domain.analysis.MoneyEvidence.minorOf(match.groupValues[2])
                if (amount == null) {
                    issues += PriceIssue(PriceIssueType.PARSE_FAILED, null, "用户材料金额无法解析")
                    return@forEach
                }
                candidates += PriceQuoteCandidate(
                    quoteId = "user-1",
                    service = query.service, plan = query.plan, region = query.region,
                    currency = currency, amountMinor = amount, period = query.period,
                    taxTerms = query.taxTerms, discountTerms = query.discountTerms,
                    benefitTerms = query.benefitTerms, newUserOnly = query.newUserOnly,
                    sourceUrl = document.url, evidenceText = match.value,
                    pageDate = document.pageDate, retrievedAtMillis = document.retrievedAtMillis,
                )
            }
        return ExtractResult(candidates, issues)
    }
}

object DefaultQuoteExtractor : QuoteExtractor {
    override suspend fun extract(query: PriceQueryRequest, documents: List<RetrievedDocument>): ExtractResult {
        val structured = JsonStructuredQuoteExtractor.extract(query, documents)
        val freeText = UserTextQuoteExtractor.extract(query, documents)
        return ExtractResult(structured.candidates + freeText.candidates, structured.issues + freeText.issues)
    }
}

data class PriceAnalysisReport(
    val query: PriceQueryRequest,
    val documents: List<RetrievedDocument>,
    val accepted: List<StructuredPriceQuote>,
    val rejected: List<PriceIssue>,
    val issues: List<PriceIssue>,
    val isFixture: Boolean,
    val onlineVerification: Boolean,
    val statusMessage: String,
) {
    val verifiedLabel: String get() = if (onlineVerification) "已在线检索（第三方来源仍非官方结论）" else "未进行在线核验"
}

/**
 * 检索 → 抽取 → 本地校验 → 冲突/过期汇总。模型/抽取器只能基于 retrieval 返回的文档，
 * 无法凭记忆给出“官方现价”；本地代码决定哪些报价可被采用。
 */
class PriceAnalysisPipeline(private val extractor: QuoteExtractor = DefaultQuoteExtractor) {

    suspend fun run(adapter: PriceRetrievalAdapter, query: PriceQueryRequest, nowMillis: Long): PriceAnalysisReport {
        query.validate()
        val bundle = adapter.retrieve(query)
        val extraction = extractor.extract(query, bundle.documents)
        val rejected = mutableListOf<PriceIssue>()
        val accepted = mutableListOf<StructuredPriceQuote>()

        for (candidate in extraction.candidates) {
            when {
                !candidate.service.equals(query.service, true) ||
                    !candidate.region.equals(query.region, true) ||
                    !candidate.plan.equals(query.plan, true) -> rejected += PriceIssue(
                    PriceIssueType.INVALID_FIELD, candidate.quoteId, "返回的服务/地区/套餐与查询条件不一致")
                candidate.currency != query.currency -> rejected += PriceIssue(
                    PriceIssueType.CURRENCY_CONFUSION, candidate.quoteId,
                    "返回币种 ${candidate.currency} 与查询币种 ${query.currency} 不一致")
                candidate.period != query.period -> rejected += PriceIssue(
                    PriceIssueType.PERIOD_CONFUSION, candidate.quoteId,
                    "返回周期 ${candidate.period} 与查询周期 ${query.period} 不一致")
                else -> when (val result = StructuredPriceValidator.validate(candidate, bundle.documents, nowMillis)) {
                    is QuoteValidation.Accepted -> accepted += result.quote
                    is QuoteValidation.Rejected -> rejected += result.reasons
                }
            }
        }

        val (marked, conflictIssues) = PriceQuoteSetAnalyzer.applyConflicts(accepted)
        val expiredIssues = marked.filter { it.verification == VerificationStatus.EXPIRED }
            .map { PriceIssue(PriceIssueType.EXPIRED, it.quoteId, "来源页面日期 ${it.pageDate} 已过期") }
        val issues = extraction.issues + conflictIssues + expiredIssues
        val online = bundle.documents.any { it.provenance == Provenance.ONLINE_HTTP }
        return PriceAnalysisReport(
            query = query,
            documents = bundle.documents,
            accepted = marked,
            rejected = rejected,
            issues = issues,
            isFixture = bundle.isFixture,
            onlineVerification = online,
            statusMessage = bundle.statusMessage,
        )
    }
}
