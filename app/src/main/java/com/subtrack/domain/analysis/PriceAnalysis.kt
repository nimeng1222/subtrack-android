package com.subtrack.domain.analysis

import com.subtrack.domain.model.Limits
import java.math.BigInteger

enum class PricePeriod { MONTHLY, YEARLY }

/** Comparable conditions must be explicitly supplied by the user; missing conditions stop ranking. */
data class ComparableQuote(val service: String, val plan: String, val region: String, val currency: String,
    val amountMinor: Long, val period: PricePeriod, val taxTerms: String, val offerTerms: String,
    val entitlement: String, val sourceUrl: String?) {
    fun validate() {
        require(service.isNotBlank() && plan.isNotBlank() && region.isNotBlank())
        require(currency.matches(Regex("[A-Z]{3}")) && Limits.isValidPriceMinor(amountMinor))
        require(listOf(service, plan, region, taxTerms, offerTerms, entitlement).all { it.length <= 200 })
    }
}

data class Comparison(val currency: String, val firstAnnualMinor: Long, val secondAnnualMinor: Long,
                      val annualDifferenceMinor: Long, val note: String)
data class CancellationSimulation(val currency: String, val estimatedAnnualAvoidedMinor: Long,
                                  val selectedCount: Int, val note: String)
data class PossibleDuplicate(val firstId: String, val secondId: String, val service: String)

object PriceAnalysis {
    private fun exactLong(value: BigInteger): Long {
        require(value >= BigInteger.valueOf(Long.MIN_VALUE) && value <= BigInteger.valueOf(Long.MAX_VALUE)) { "金额超出范围" }
        return value.toLong()
    }
    private fun annual(q: ComparableQuote): BigInteger = q.amountMinor.toBigInteger() *
        if (q.period == PricePeriod.MONTHLY) 12.toBigInteger() else BigInteger.ONE

    /** No currency/entitlement conversion and no implicit assumption about taxes or promotions. */
    fun compare(a: ComparableQuote, b: ComparableQuote): Comparison {
        a.validate(); b.validate()
        require(a.service.equals(b.service, true) && a.region.equals(b.region, true) && a.currency == b.currency) {
            "服务、地区或币种不一致，不能直接比较"
        }
        require(listOf(a.taxTerms, a.offerTerms, a.entitlement, b.taxTerms, b.offerTerms, b.entitlement).all { it.isNotBlank() }) {
            "请确认税费、优惠和权益"
        }
        require(a.taxTerms == b.taxTerms && a.offerTerms == b.offerTerms && a.entitlement == b.entitlement) {
            "税费、优惠或权益不同，不能直接比较"
        }
        val first = exactLong(annual(a)); val second = exactLong(annual(b))
        return Comparison(a.currency, first, second, Math.subtractExact(first, second),
            "金额为按所填条件计算的年化参考，来源和适用条件仍需用户核实。")
    }

    /** This is a forward-looking scenario; it never modifies or sums actual paid history. */
    fun simulateCancellation(quotes: List<ComparableQuote>): CancellationSimulation {
        require(quotes.size in 1..1000)
        quotes.forEach { it.validate() }
        val currency = quotes.first().currency
        require(quotes.all { it.currency == currency }) { "不同币种不能直接求和" }
        val amount = exactLong(quotes.fold(BigInteger.ZERO) { sum, q -> sum + annual(q) })
        return CancellationSimulation(currency, amount, quotes.size, "假设未来各项费用不变的预计少支出，并非已节省金额。")
    }

    /** Exact normalized names only; reported as a possible duplicate, not proof of overlap. */
    fun possibleDuplicates(items: List<Pair<String, String>>): List<PossibleDuplicate> {
        require(items.size <= 1000 && items.all { it.first.isNotBlank() && it.second.length <= 200 })
        require(items.map { it.first }.distinct().size == items.size)
        return items.groupBy { it.second.trim().lowercase() }.filterKeys { it.isNotBlank() }.values.flatMap { group ->
            group.indices.flatMap { i -> (i + 1 until group.size).map { j ->
                PossibleDuplicate(group[i].first, group[j].first, group[i].second)
            } }
        }
    }
}
