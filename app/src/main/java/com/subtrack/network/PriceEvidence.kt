package com.subtrack.network

import com.subtrack.data.backup.*

/** Only locally parsed structured quotes bind all pricing dimensions. Model labels are not evidence. */
object PriceEvidence {
    fun structured(q: PriceQuestion, amount: Long, quoteId: String?): Boolean {
        if (!q.material.trimStart().startsWith("{")) return false
        return runCatching {
            val quotes = parseObject(q.material).items("quotes").map { it.asObject("quote") }
            require(quotes.size in 1..100)
            val selected = quotes.single { it.text("id") == quoteId }
            selected.text("service") == q.service && selected.text("region") == q.region &&
                selected.text("plan") == q.plan && selected.text("currency") == q.currency &&
                selected.text("billingPeriod") == q.period && selected.text("conditions") == q.conditions &&
                selected.text("sourceUrl") == q.sourceUrl && selected.number("amountMinor") == amount
        }.getOrDefault(false)
    }

    fun validateLegacy(q: PriceQuestion, evidence: String, amount: Long) {
        // A long quote or neighboring offer cannot supply unrelated amount and period tokens.
        // Legacy free text is informational only, never eligible to prefill a price.
        val money = Regex("\\b[A-Z]{3}\\s*[0-9]+(?:\\.[0-9]{1,2})?\\b").findAll(q.material).toList()
        require(money.size == 1) { "多报价材料缺少逐报价结构化关联，不允许采用价格" }
        val word = if (q.period == "MONTHLY") "month" else "year"
        val opposite = if (q.period == "MONTHLY") "year" else "month"
        require(!Regex("\\b$opposite(?:ly)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(q.material)) { "报价周期存在歧义" }
        val match = Regex("\\b${q.currency}\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:per\\s+|/\\s*)$word\\b", RegexOption.IGNORE_CASE)
            .find(evidence) ?: error("价格与周期未直接绑定")
        require(match.groupValues[1].toBigDecimal().movePointRight(2).longValueExact() == amount) { "引用价格不匹配" }
    }
}
