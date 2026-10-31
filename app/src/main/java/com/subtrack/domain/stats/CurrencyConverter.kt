package com.subtrack.domain.stats

import com.subtrack.core.money.sumMoney
import com.subtrack.domain.model.Subscription

/**
 * 多币种换算（L2，默认关闭）。
 *
 * 文档 §1.5 把"多币种实时汇率"列为不做（需网络），但留了 **P1 手动汇率** 的口子。
 * 这里就是那个手动版：
 * - 不联网，汇率完全由用户填；
 * - 不改动任何已存数据，只在**展示层**折算——原始 priceMinor 与 currency 保持原样，
 *   这样导出、统计、备份都不受影响，关掉功能后立刻回到原状。
 *
 * 金额全程 Long 分，换算用"先乘后除 + 四舍五入"，避免浮点进到钱上。
 */
object CurrencyConverter {

    /** 支持的币种（只列常见的，避免做成一堆没人用的下拉）。 */
    val SUPPORTED = listOf(
        "CNY" to "人民币",
        "USD" to "美元",
        "JPY" to "日元",
        "EUR" to "欧元",
        "HKD" to "港币",
        "GBP" to "英镑",
    )

    fun nameOf(code: String): String =
        SUPPORTED.firstOrNull { it.first == code }?.second ?: code

    /**
     * 把 [minor] 分（[from] 币种）折算成人民币分。
     *
     * 量纲约定（容易搞错，写死在这里）：**汇率是"1 单位外币值多少分人民币"**。
     * 例：USD → 72000 表示 1 USD = 72000 分 = 720.00 元？不对，是 7.20 元 = 720 分。
     * 所以正确取值是 USD → 720，即 1 美元 = 720 分人民币。
     *
     * 折算公式：`minor(外币分) × 汇率(分人民币/外币) / 100(外币分/外币单位)`
     * 即先乘后除，只做一次四舍五入。
     *
     * @return 折算后的分值；若缺汇率则返回 null（调用方应显示原值 + 提示"未设汇率"）。
     */
    fun toCnyMinor(minor: Long, from: String, fxRates: Map<String, Long>): Long? {
        if (from == "CNY") return minor
        val table = fxRates as? FxRateTable
        val precise = table?.onlineNanos?.get(from)
        if (precise != null && from !in table.manual) {
            if (!FxPrecision.valid(precise)) return null
            val scale = FxPrecision.SCALE.toBigInteger()
            return com.subtrack.core.money.AmountMath.display((minor.toBigInteger() * precise.toBigInteger() + scale / 2.toBigInteger()) / scale)
        }
        val rate = if (table != null) table.manual[from] else fxRates[from]
        if (rate == null) return null
        if (!com.subtrack.domain.model.Limits.isValidFxRate(rate)) return null
        return com.subtrack.core.money.AmountMath.display((minor.toBigInteger() * rate.toBigInteger() + 50.toBigInteger()) / 100.toBigInteger())
    }

    /** 该订阅在"折算后"的口径下值多少人民币分。缺汇率时返回 null。 */
    fun subscriptionCnyMinor(sub: Subscription, fxRates: Map<String, Long>): Long? =
        toCnyMinor(sub.priceMinor, sub.currency, fxRates)

    /**
     * 把一组订阅按汇率折算后重新算出年化。
     *
     * 有意不递归调用 Annualizer：那条路径要求 Subscription 自带已换算的 priceMinor，
     * 这里直接对"折算后的单价"调同样的系数，逻辑单一、没有第二份系数表。
     */
    fun annualizedCnyMinor(subs: List<Subscription>, fxRates: Map<String, Long>): Long? {
        if (FxAmounts.hasOnline(fxRates)) {
            if (missingRates(subs, fxRates).isNotEmpty()) return null
            return FxAmounts.display(FxAmounts.sumAnnual(subs, fxRates))
        }
        var total = 0L
        for (sub in subs.filter { it.isForecastIncluded }) {
            val cny = subscriptionCnyMinor(sub, fxRates) ?: return null
            val annual = Annualizer.annualizeMinor(cny, sub.cycle, sub.customCycleDays)
            total = com.subtrack.core.money.AmountMath.add(total, annual)
        }
        return total
    }

    /** 是否所有非人民币条目都已经配好汇率（决定是否要提示用户去补）。 */
    fun missingRates(subs: List<Subscription>, fxRates: Map<String, Long>): List<String> =
        subs.filter { it.isForecastIncluded && it.currency != "CNY" && toCnyMinor(1L, it.currency, fxRates) == null }
            .map { it.currency }
            .distinct()

    /**
     * 一组流水的折算合计。fxRates 为 null 时完全按原样相加（默认口径）；
     * 非 null 时逐笔折算成人民币，缺汇率的那笔**跳过**而不是当人民币硬加，
     * 与订阅侧"缺汇率排除、missingRates 提示"的口径一致。
     *
     * @return (折算后合计, 参与合计的笔数)
     */
    fun paymentsCnySummary(
        records: List<com.subtrack.domain.repository.PaymentRecord>,
        fxRates: Map<String, Long>?,
    ): Pair<Long, Int> = when (fxRates) {
        null -> records.sumMoney { it.amountMinor } to records.size
        else -> {
            if (FxAmounts.hasOnline(fxRates)) {
                var total = java.math.BigInteger.ZERO
                var count = 0
                for (r in records) {
                    val scaled = FxAmounts.scaled(r.amountMinor, r.currency, fxRates) ?: continue
                    total += scaled; count++
                }
                FxAmounts.display(total) to count
            } else {
            var total = 0L
            var count = 0
            for (r in records) {
                val cny = toCnyMinor(r.amountMinor, r.currency, fxRates) ?: continue
                total = com.subtrack.core.money.AmountMath.add(total, cny)
                count += 1
            }
                total to count
            }
        }
    }

    /**
     * 折算年化（宽松版）：缺汇率的条目跳过而不是返回 null。
     * 小组件这类"只展示一个数"的场景用；与 StatsEngine 的排除口径一致。
     */
    fun annualizedCnyMinorSkipMissing(subs: List<Subscription>, fxRates: Map<String, Long>): Long =
        if (FxAmounts.hasOnline(fxRates)) FxAmounts.display(FxAmounts.sumAnnual(subs, fxRates)) else subs.filter { it.isForecastIncluded }.fold(0L) { acc, sub ->
            val cny = toCnyMinor(sub.priceMinor, sub.currency, fxRates) ?: return@fold acc
            com.subtrack.core.money.AmountMath.add(acc, Annualizer.annualizeMinor(cny, sub.cycle, sub.customCycleDays))
        }
}
