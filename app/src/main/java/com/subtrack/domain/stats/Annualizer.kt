package com.subtrack.domain.stats

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription

/**
 * 整数年化。金额一律 Long 分，全程不出现 Double/Float。
 *
 * 系数：周 52 / 双周 26 / 四周 13 / 月 12 / 季 4 / 半年 2 / 年 1 / 自定义 365÷n / 一次性 0。
 * 最容易错的一格是 FOUR_WEEKLY：写成 12 会低估 7.7%。
 */
object Annualizer {

    fun annualizeExact(priceMinor: Long, cycle: BillingCycleType, customCycleDays: Int?): java.math.BigInteger {
        return annualizeExact(priceMinor.toBigInteger(), cycle, customCycleDays)
    }

    fun annualizeExact(price: java.math.BigInteger, cycle: BillingCycleType, customCycleDays: Int?): java.math.BigInteger {
        val multiplier = when (cycle) {
            BillingCycleType.MONTHLY -> 12L
            BillingCycleType.QUARTERLY -> 4L
            BillingCycleType.SEMIANNUAL -> 2L
            BillingCycleType.YEARLY -> 1L
            BillingCycleType.WEEKLY -> 52L
            BillingCycleType.BIWEEKLY -> 26L
            BillingCycleType.FOUR_WEEKLY -> 13L
            BillingCycleType.CUSTOM_DAYS -> 365L
            BillingCycleType.ONE_TIME -> 0L
        }
        val result = price * multiplier.toBigInteger()
        return if (cycle == BillingCycleType.CUSTOM_DAYS) result / (customCycleDays?.takeIf { it > 0 } ?: 1).toBigInteger() else result
    }

    fun annualizeMinor(priceMinor: Long, cycle: BillingCycleType, customCycleDays: Int?): Long =
        com.subtrack.core.money.AmountMath.display(annualizeExact(priceMinor, cycle, customCycleDays))

    fun monthlyAverageMinor(annualSumMinor: Long): Long =
        if (annualSumMinor == Long.MAX_VALUE) Long.MAX_VALUE else com.subtrack.core.money.AmountMath.monthly(annualSumMinor.toBigInteger())

    fun forecastExact(subs: List<Subscription>): java.math.BigInteger =
        subs.filter { it.isForecastIncluded }.fold(java.math.BigInteger.ZERO) { acc, s -> acc + annualizeExact(s.priceMinor, s.cycle, s.customCycleDays) }

    fun forecastAnnualMinor(subs: List<Subscription>): Long = com.subtrack.core.money.AmountMath.display(forecastExact(subs))
    fun forecastMonthlyMinor(subs: List<Subscription>): Long = com.subtrack.core.money.AmountMath.monthly(forecastExact(subs))
}
