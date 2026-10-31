package com.subtrack.domain.stats

import com.subtrack.core.money.sumMoney
import com.subtrack.domain.model.Subscription
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 本月将扣（现金流口径，AC-36）。
 *
 * 与"预计月均"的区别：这里的金额**不做年化**，只看本月内真的有扣款日的条目，
 * 按面额直接相加。用户问"这个月还要扣多少钱"时，指的是这个数。
 */
object MonthlyCashflow {

    fun dueThisMonth(subs: List<Subscription>, today: LocalDate): List<Subscription> {
        val start = today.withDayOfMonth(1)
        val end = today.with(TemporalAdjusters.lastDayOfMonth())
        return subs.filter { sub ->
            val next = sub.nextChargeDate ?: return@filter false
            sub.isForecastIncluded && !next.isBefore(start) && !next.isAfter(end)
        }.sortedBy { it.nextChargeDate }
    }

    fun dueThisMonthMinor(subs: List<Subscription>, today: LocalDate): Long =
        dueThisMonth(subs, today).sumMoney { it.priceMinor }

    fun dueThisMonthCount(subs: List<Subscription>, today: LocalDate): Int =
        dueThisMonth(subs, today).size

    /**
     * 本月将扣的折算口径。fxRates 为 null（多币种未启用）时完全按原样相加；
     * 非 null 时逐条折算成人民币，缺汇率的条目**跳过**而不是当人民币硬算——
     * 与 StatsEngine 的口径一致，缺什么币种由 missingRates 单独提示。
     */
    fun dueThisMonthMinor(
        subs: List<Subscription>,
        today: LocalDate,
        fxRates: Map<String, Long>?,
    ): Long = when (fxRates) {
        null -> dueThisMonthMinor(subs, today)
        else -> if (FxAmounts.hasOnline(fxRates)) FxAmounts.display(dueThisMonth(subs, today)
            .fold(java.math.BigInteger.ZERO) { sum, sub -> sum + (FxAmounts.scaled(sub.priceMinor, sub.currency, fxRates) ?: java.math.BigInteger.ZERO) })
        else dueThisMonth(subs, today).sumMoney { sub -> CurrencyConverter.toCnyMinor(sub.priceMinor, sub.currency, fxRates) ?: 0L }
    }
}
