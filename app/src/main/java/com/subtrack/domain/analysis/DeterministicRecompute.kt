package com.subtrack.domain.analysis

import com.subtrack.domain.model.Limits
import java.math.BigInteger

/**
 * N5 所有金额结论的唯一计算入口。
 *
 * 模型只给“建议”，任何月付/年付差额、年化、汇率折算、节省总额都由这里用
 * Long 分 + BigInteger 精确复算，绝不信任模型心算，也不让二进制浮点进到钱上。
 * 不可比条件（币种/税费/优惠/权益/新老用户不同，或来源过期/冲突）一律显式返回，
 * 不给“最便宜”结论。
 */
data class RecomputedComparison(
    val comparable: Boolean,
    val currency: String,
    val firstAnnualMinor: Long?,
    val secondAnnualMinor: Long?,
    val annualDifferenceMinor: Long?,
    val reason: String,
    val note: String,
)

data class SavingsSimulation(
    val comparable: Boolean,
    val currency: String,
    val estimatedAnnualAvoidedMinor: Long?,
    val selectedCount: Int,
    val reason: String,
    val note: String,
)

object DeterministicRecompute {
    private val TWELVE = BigInteger.valueOf(12)
    private val SIX = BigInteger.valueOf(6)
    private val TWO = BigInteger.valueOf(2)
    private val FX_SCALE = BigInteger.valueOf(1_000_000_000L)
    const val FX_SCALE_LONG = 1_000_000_000L
    const val FX_MAX = 10_000L * FX_SCALE_LONG

    private fun exact(value: BigInteger): Long {
        require(value >= BigInteger.valueOf(Long.MIN_VALUE) && value <= BigInteger.valueOf(Long.MAX_VALUE)) { "金额超出范围" }
        return value.toLong()
    }

    fun annualMinor(amountMinor: Long, period: PricePeriod): Long {
        require(Limits.isValidPriceMinor(amountMinor))
        return exact(amountMinor.toBigInteger() * if (period == PricePeriod.MONTHLY) TWELVE else BigInteger.ONE)
    }

    fun monthlyEquivalentMinor(amountMinor: Long, period: PricePeriod): Long {
        require(Limits.isValidPriceMinor(amountMinor))
        return if (period == PricePeriod.MONTHLY) amountMinor else exact((amountMinor.toBigInteger() + SIX) / TWELVE)
    }

    fun annualDifferenceMinor(firstAmountMinor: Long, firstPeriod: PricePeriod,
                              secondAmountMinor: Long, secondPeriod: PricePeriod): Long =
        Math.subtractExact(annualMinor(firstAmountMinor, firstPeriod), annualMinor(secondAmountMinor, secondPeriod))

    /** 在线汇率口径：rateNanos = 1 外币单位值多少 1e-9 人民币。先乘后除一次四舍五入。 */
    fun convertToCnyMinor(amountMinor: Long, rateNanos: Long): Long {
        require(Limits.isValidPriceMinor(amountMinor))
        require(rateNanos in 1..FX_MAX) { "汇率无效" }
        val value = (amountMinor.toBigInteger() * rateNanos.toBigInteger() + FX_SCALE / TWO) / FX_SCALE
        return exact(value)
    }

    fun compare(first: StructuredPriceQuote, second: StructuredPriceQuote): RecomputedComparison {
        val currency = first.currency
        if (first.currency != second.currency) {
            return notComparable(currency, "币种不同（${first.currency} vs ${second.currency}），不做直接折算比较")
        }
        if (!first.service.equals(second.service, true) || !first.region.equals(second.region, true)) {
            return notComparable(currency, "服务或地区不一致，不能直接比较")
        }
        if (first.taxTerms.trim().lowercase() != second.taxTerms.trim().lowercase()) {
            return notComparable(currency, "税费条件不同，金额不等价")
        }
        if (first.discountTerms.trim().lowercase() != second.discountTerms.trim().lowercase()) {
            return notComparable(currency, "优惠条件不同，金额不等价")
        }
        if (first.benefitTerms.trim().lowercase() != second.benefitTerms.trim().lowercase()) {
            return notComparable(currency, "权益不同，金额不等价")
        }
        if (first.newUserOnly != second.newUserOnly) {
            return notComparable(currency, "新老用户条件不同，金额不等价")
        }
        if (first.verification == VerificationStatus.EXPIRED || second.verification == VerificationStatus.EXPIRED) {
            return notComparable(currency, "来源已过期，不能据此下结论")
        }
        if (first.verification == VerificationStatus.CONFLICT || second.verification == VerificationStatus.CONFLICT) {
            return notComparable(currency, "来源存在冲突，不能据此下结论")
        }
        val firstAnnual = annualMinor(first.amountMinor, first.period)
        val secondAnnual = annualMinor(second.amountMinor, second.period)
        return RecomputedComparison(
            comparable = true, currency = currency, firstAnnualMinor = firstAnnual,
            secondAnnualMinor = secondAnnual, annualDifferenceMinor = Math.subtractExact(firstAnnual, secondAnnual),
            reason = "可比", note = "按原币与所填条件由本地精确复算的年化参考，未做汇率或权益折算；来源与适用条件仍需用户核实。",
        )
    }

    fun savings(quotes: List<StructuredPriceQuote>): SavingsSimulation {
        if (quotes.isEmpty()) return SavingsSimulation(false, "", null, 0, "未选择任何条目", "无可用比较对象。")
        val currency = quotes.first().currency
        if (quotes.any { it.currency != currency }) {
            return SavingsSimulation(false, currency, null, quotes.size, "不同币种不能直接求和", "请先分别折算再模拟。")
        }
        if (quotes.any { it.verification == VerificationStatus.EXPIRED }) {
            return SavingsSimulation(false, currency, null, quotes.size, "包含已过期来源", "过期价格不能作为节省依据。")
        }
        if (quotes.any { it.verification == VerificationStatus.CONFLICT }) {
            return SavingsSimulation(false, currency, null, quotes.size, "包含来源冲突条目", "冲突价格不能直接求和。")
        }
        var total = BigInteger.ZERO
        for (quote in quotes) total += annualMinor(quote.amountMinor, quote.period).toBigInteger()
        return SavingsSimulation(
            comparable = true, currency = currency, estimatedAnnualAvoidedMinor = exact(total),
            selectedCount = quotes.size, reason = "可比",
            note = "假设未来各项费用不变的预计少支出，并非已经节省的真实金额。",
        )
    }

    private fun notComparable(currency: String, reason: String) = RecomputedComparison(
        comparable = false, currency = currency, firstAnnualMinor = null, secondAnnualMinor = null,
        annualDifferenceMinor = null, reason = reason, note = "不同税费/币种/权益不等价时，本地代码拒绝给出最便宜结论。",
    )
}
