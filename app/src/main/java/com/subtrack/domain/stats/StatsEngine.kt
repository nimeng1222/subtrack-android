package com.subtrack.domain.stats

import com.subtrack.core.money.AmountMath
import com.subtrack.core.money.sumMoney
import java.math.BigInteger
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.state.DisplayStateResolver
import com.subtrack.domain.state.SubscriptionDisplayState
import java.time.LocalDate

/**
 * 统计口径的唯一实现。UI 只消费这里的结果，禁止在 Composable 里自己算金额。
 *
 * 三条铁律（来自主文档 §5.4）：
 * 1. 年度是唯一事实来源，月均只能由年度派生；禁止先算月均再 ×12 反推。
 * 2. 取整只发生在展示层，内部值全程整数分，Σ年化 精确可逆。
 * 3. 一次性买断不进年化，单独汇总。
 */
object StatsEngine {

    /** 单个分类的聚合结果。 */
    data class CategorySlice(
        val categoryId: String,
        val annualMinor: Long,
        val count: Int,
        val exactAnnual: BigInteger = annualMinor.toBigInteger(),
        val exactTotal: BigInteger? = null,
    ) {
        /** 万分比，用于避免浮点。UI 展示时再转百分比。 */
        fun basisPoints(total: Long): Long =
            if ((exactTotal ?: total.toBigInteger()).signum() <= 0) 0L else
                ((exactAnnual * 10_000.toBigInteger() + (exactTotal ?: total.toBigInteger()) / 2.toBigInteger()) / (exactTotal ?: total.toBigInteger())).toLong()
    }

    /** AC-35：点开任一数字要能展开"公式 + 参与计算的条目清单"。 */
    data class Breakdown(
        val title: String,
        val formula: String,
        val lines: List<Line>,
        val totalMinor: Long,
    ) {
        data class Line(val name: String, val detail: String, val annualMinor: Long)
    }

    /** 一组订阅在某个折算口径下的年化/月均展开。 */
    private class AnnualizedEntries(
        val lines: List<Breakdown.Line>,
        val exactAnnual: BigInteger,
        val scaledAnnual: BigInteger? = null,
    ) {
        val annualTotalMinor: Long get() = AmountMath.display(exactAnnual)
        val monthlyTotalMinor: Long get() = scaledAnnual?.let { FxAmounts.monthly(it) } ?: AmountMath.monthly(exactAnnual)
    }

    /**
     * 明细与总额共用一套折算：fxRates 为 null 时按原样；非 null 时逐条折算，
     * 缺汇率的条目排除（与 snapshot 的排除口径一致，缺的币种由 missingRates 提示）。
     */
    private fun annualizeEntries(subs: List<Subscription>, fxRates: Map<String, Long>?): AnnualizedEntries {
        val lines = subs.filter { it.isForecastIncluded }
            .mapNotNull { sub ->
                val price = when {
                    fxRates == null || sub.currency == "CNY" -> sub.priceMinor
                    else -> CurrencyConverter.toCnyMinor(sub.priceMinor, sub.currency, fxRates)
                        ?: return@mapNotNull null
                }
                Breakdown.Line(
                    name = sub.name,
                    detail = if (FxAmounts.hasOnline(fxRates) && sub.currency != "CNY") {
                        val table = fxRates as FxRateTable
                        val nanos = if (sub.currency in table.manual) table.manual.getValue(sub.currency) * 10_000_000L else table.onlineNanos.getValue(sub.currency)
                        val rate = java.math.BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString()
                        "${coefficient(sub.cycle, sub.customCycleDays)} × ${sub.currency} ${yuan(sub.priceMinor).removePrefix("¥")} × $rate 人民币/单位"
                    } else "${coefficient(sub.cycle, sub.customCycleDays)} × ${yuan(price)}",
                    annualMinor = if (FxAmounts.hasOnline(fxRates)) FxAmounts.annualScaled(sub, fxRates)?.let { FxAmounts.display(it) } ?: 0L
                        else Annualizer.annualizeMinor(price, sub.cycle, sub.customCycleDays),
                )
            }
            .sortedByDescending { it.annualMinor }
        return AnnualizedEntries(lines, exactAnnualTotal(subs, fxRates),
            if (FxAmounts.hasOnline(fxRates)) FxAmounts.sumAnnual(subs, fxRates) else null)
    }

    data class Snapshot(
        /** 现金流口径：本月内实际有扣款日的条目金额合计。 */
        val dueThisMonthMinor: Long,
        val dueThisMonthCount: Int,
        /** 承诺口径：Σ年化 / 12。 */
        val forecastMonthlyMinor: Long,
        val forecastAnnualMinor: Long,
        /** 真实口径：近 90 天流水合计 / 3；覆盖不足 90 天时为 null（AC-36a）。 */
        val actualMonthlyMinor: Long?,
        /** 一次性买断的累计支出（AC-40），不混入月均。 */
        val oneTimeTotalMinor: Long,
        val oneTimeCount: Int,
        /** 待确认条目（AC-17：仍计入统计，但要单独标注）。 */
        val pendingCount: Int,
        val pendingMinor: Long,
        /** 试运转正后会增加的月均（AC-49）。 */
        val trialCount: Int,
        val trialMonthlyDeltaMinor: Long,
        val categories: List<CategorySlice>,
        val activeCount: Int,
        /** 流水覆盖天数，用于 AC-36a 的判定。 */
        val actualCoverageDays: Long,
        val actualSampleCount: Int,
    )

    /**
     * @param payments 近 90 天流水（金额合计与笔数）
     * @param firstPaymentDate 库里最早一条流水的日期，用于判断覆盖是否满 90 天
     */
    /**
     * @param fxRates 多币种汇率（1 单位外币 = 多少分人民币）。
     *   - null 表示多币种未启用：**完全按原样计算**，不做任何折算（默认行为）；
     *   - 非 null 表示已启用：外币条目按汇率折算成人民币后再汇总。
     *
     *   缺某币种汇率时怎么办：**把该条排除在金额汇总外**，而不是当人民币硬算。
     *   后者会让 10 美元显示成 10 元，用户看到的月均凭空缩水——比显示不出来误导得多。
     *   被排除的条数仍会出现在 activeCount 里，UI 用 missingRates 提示去补汇率。
     */
    fun snapshot(
        subs: List<Subscription>,
        today: LocalDate,
        dueThisMonthMinor: Long,
        dueThisMonthCount: Int,
        paymentsTotalMinor: Long,
        paymentsCount: Int,
        firstPaymentDate: LocalDate?,
        fxRates: Map<String, Long>? = null,
    ): Snapshot {
        val included = subs.filter { it.isForecastIncluded }

        /** 该条折算成人民币后的单价；不需要折算或无法折算时返回 null。 */
        fun cnyPrice(sub: Subscription): Long? {
            if (fxRates == null || sub.currency == "CNY") return sub.priceMinor
            return CurrencyConverter.toCnyMinor(sub.priceMinor, sub.currency, fxRates)
        }

        val converted = included.mapNotNull { sub -> cnyPrice(sub)?.let { sub to it } }
        val exactAnnual = exactAnnualTotal(subs, fxRates)
        val annual = AmountMath.display(exactAnnual)
        val scaledAnnual = if (FxAmounts.hasOnline(fxRates)) FxAmounts.sumAnnual(subs, fxRates) else null
        val monthly = scaledAnnual?.let { FxAmounts.monthly(it) } ?: AmountMath.monthly(exactAnnual)

        val pending = included.filter { sub ->
            (DisplayStateResolver.resolve(sub, today, hasPaymentOnDueDate = false)
                is SubscriptionDisplayState.PendingConfirm)
        }
        val trial = included.filter { it.status == com.subtrack.domain.model.SubscriptionStatus.TRIAL }
        // 一次性买断的累计支出（AC-40）：按"未删除的全部买断"汇总，
        // 不看 isForecastIncluded——确认支付后状态变 EXPIRED，若按预测口径筛，
        // 刚确认的买断会立刻从累计支出里消失。
        val oneTime = subs.filter { it.cycle == BillingCycleType.ONE_TIME && !it.isDeleted }

        val categories = converted
            .groupBy { (sub, _) -> sub.categoryId }
            .map { (id, list) ->
                CategorySlice(
                    categoryId = id,
                    annualMinor = if (scaledAnnual != null) FxAmounts.display(FxAmounts.sumAnnual(list.map { it.first }, fxRates)) else list.sumMoney { (sub, price) ->
                        Annualizer.annualizeMinor(price, sub.cycle, sub.customCycleDays)
                    },
                    count = list.size,
                    exactAnnual = if (scaledAnnual != null) FxAmounts.sumAnnual(list.map { it.first }, fxRates) else list.fold(BigInteger.ZERO) { acc, (sub, price) -> acc + Annualizer.annualizeExact(price, sub.cycle, sub.customCycleDays) },
                    exactTotal = scaledAnnual ?: exactAnnual,
                )
            }
            .filter { it.annualMinor > 0 }
            .sortedByDescending { it.exactAnnual }

        // AC-36a：流水覆盖不足 90 天才显示"记录满 90 天后可核对"，不足时不外推数字
        val coverage = firstPaymentDate?.let {
            java.time.temporal.ChronoUnit.DAYS.between(it, today)
        } ?: 0L
        val actual = if (paymentsCount > 0 && coverage >= 90L) {
            if (paymentsTotalMinor == Long.MAX_VALUE) Long.MAX_VALUE else paymentsTotalMinor / 3
        } else null

        return Snapshot(
            dueThisMonthMinor = dueThisMonthMinor,
            dueThisMonthCount = dueThisMonthCount,
            forecastMonthlyMinor = monthly,
            forecastAnnualMinor = annual,
            actualMonthlyMinor = actual,
            oneTimeTotalMinor = if (scaledAnnual != null) FxAmounts.display(oneTime.fold(BigInteger.ZERO) { sum, s -> sum + (FxAmounts.scaled(s.priceMinor, s.currency, fxRates) ?: BigInteger.ZERO) }) else oneTime.sumMoney { cnyPrice(it) ?: 0L },
            oneTimeCount = oneTime.size,
            pendingCount = pending.size,
            pendingMinor = if (scaledAnnual != null) FxAmounts.display(FxAmounts.sumAnnual(pending, fxRates)) else pending.sumMoney { sub ->
                val price = cnyPrice(sub) ?: 0L
                Annualizer.annualizeMinor(price, sub.cycle, sub.customCycleDays)
            },
            trialCount = trial.size,
            trialMonthlyDeltaMinor = if (scaledAnnual != null) FxAmounts.monthly(FxAmounts.sumAnnual(trial, fxRates)) else AmountMath.monthly(exactAnnualTotal(trial, fxRates)),
            categories = categories,
            activeCount = included.size,
            actualCoverageDays = coverage,
            actualSampleCount = paymentsCount,
        )
    }

    /** AC-36b：两口径差值洞察。返回 null 表示没有值得说的结论。 */
    data class Insight(val headline: String, val detail: String, val suggestAdd: Boolean)

    fun insight(forecastMonthlyMinor: Long, actualMonthlyMinor: Long?, sampleCount: Int): Insight? {
        if (actualMonthlyMinor == null || actualMonthlyMinor <= 0L || actualMonthlyMinor == Long.MAX_VALUE || forecastMonthlyMinor == Long.MAX_VALUE) return null
        val diff = forecastMonthlyMinor - actualMonthlyMinor
        val absDiff = kotlin.math.abs(diff)
        val ratio = absDiff.toDouble() / actualMonthlyMinor
        // 阈值：差额超过 20% 且绝对值超过 10 元才提示，否则是噪声
        if (ratio <= 0.20 || absDiff < 1_000L) {
            return Insight(
                headline = "两个口径基本吻合",
                detail = "差 ${yuan(absDiff)}，说明台账和实际扣款对得上（近 90 天 $sampleCount 笔）",
                suggestAdd = false,
            )
        }
        return if (diff < 0) {
            Insight(
                headline = "预计比实际低 ${yuan(absDiff)}",
                detail = "可能有订阅还没录入（近 90 天 $sampleCount 笔流水）",
                suggestAdd = true,
            )
        } else {
            Insight(
                headline = "预计比实际高 ${yuan(absDiff)}",
                detail = "可能有订阅已不再扣款，可在详情页标记取消",
                suggestAdd = false,
            )
        }
    }

    /**
     * AC-35：预计月均的展开明细。总额 = Σ年化 ÷ 12，与统计卡的"预计月均"同口径；
     * 年度合计单独由 [annualBreakdown] 呈现，两个数不再混在一个字段里。
     * [fxRates] 与 [snapshot] 同语义：null = 不折算；非 null = 逐条折算、缺汇率排除。
     */
    fun monthlyBreakdown(
        subs: List<Subscription>,
        fxRates: Map<String, Long>? = null,
    ): Breakdown {
        val entries = annualizeEntries(subs, fxRates)
        return Breakdown(
            title = "预计月均",
            formula = if (FxAmounts.hasOnline(fxRates)) "原币金额按所示汇率年化后汇总 ÷ 12，再四舍五入到分；各条显示值单独舍入。" else "Σ(单条年化) ÷ 12，四舍五入到分。年化系数见每条明细。",
            lines = entries.lines,
            totalMinor = entries.monthlyTotalMinor,
        )
    }

    /** AC-35：年度总支出的展开明细，总额保留年度合计。 */
    fun annualBreakdown(
        subs: List<Subscription>,
        fxRates: Map<String, Long>? = null,
    ): Breakdown {
        val entries = annualizeEntries(subs, fxRates)
        return Breakdown(
            title = "年度总支出",
            formula = if (FxAmounts.hasOnline(fxRates)) "原币金额按所示汇率年化后汇总，再四舍五入到分；各条显示值单独舍入。" else "各条按计费周期折算到一年后求和。这是统计的唯一事实来源，月均由它派生。",
            lines = entries.lines,
            totalMinor = entries.annualTotalMinor,
        )
    }

    /** AC-35：本月将扣的展开明细（现金流口径，不做年化；折算口径与统计卡一致）。 */
    fun dueBreakdown(
        subs: List<Subscription>,
        today: LocalDate,
        fxRates: Map<String, Long>? = null,
    ): Breakdown {
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth())
        val due = subs.filter { sub ->
            val next = sub.nextChargeDate ?: return@filter false
            sub.isForecastIncluded && !next.isBefore(monthStart) && !next.isAfter(monthEnd)
        }.sortedBy { it.nextChargeDate }
        val lines = due.mapNotNull { sub ->
            val price = when {
                fxRates == null || sub.currency == "CNY" -> sub.priceMinor
                else -> CurrencyConverter.toCnyMinor(sub.priceMinor, sub.currency, fxRates) ?: return@mapNotNull null
            }
            Breakdown.Line(
                name = sub.name,
                detail = "${sub.nextChargeDate?.monthValue}月${sub.nextChargeDate?.dayOfMonth}日扣",
                annualMinor = price,
            )
        }
        return Breakdown(
            title = "本月将扣",
            formula = "只看本月内有扣款日的条目，按金额直接相加（现金流口径，不做年化）。",
            lines = lines,
            totalMinor = if (FxAmounts.hasOnline(fxRates)) MonthlyCashflow.dueThisMonthMinor(subs, today, fxRates) else lines.sumMoney { it.annualMinor },
        )
    }

    private fun exactAnnualTotal(subs: List<Subscription>, fxRates: Map<String, Long>?): BigInteger =
        if (FxAmounts.hasOnline(fxRates)) FxAmounts.round(FxAmounts.sumAnnual(subs, fxRates)) else subs.filter { it.isForecastIncluded }.fold(BigInteger.ZERO) { total, sub ->
            val price = if (fxRates == null) sub.priceMinor else CurrencyConverter.toCnyMinor(sub.priceMinor, sub.currency, fxRates)
            total + (price?.let { Annualizer.annualizeExact(it, sub.cycle, sub.customCycleDays) } ?: BigInteger.ZERO)
        }

    /** 年化系数的可读形式，用于展开面板。 */
    fun coefficient(cycle: BillingCycleType, customDays: Int?): String = when (cycle) {
        BillingCycleType.MONTHLY -> "×12"
        BillingCycleType.QUARTERLY -> "×4"
        BillingCycleType.SEMIANNUAL -> "×2"
        BillingCycleType.YEARLY -> "×1"
        BillingCycleType.WEEKLY -> "×52"
        BillingCycleType.BIWEEKLY -> "×26"
        BillingCycleType.FOUR_WEEKLY -> "×13"
        BillingCycleType.CUSTOM_DAYS -> "×365/${customDays ?: 0}"
        BillingCycleType.ONE_TIME -> "不计"
    }

    /** 整数分 → "¥12.34"，供展开面板用；不使用 Money 以免循环依赖。 */
    private fun yuan(minor: Long): String {
        if (minor == Long.MAX_VALUE) return "金额超出显示范围（原始数据保留）"
        val neg = minor < 0
        val v = kotlin.math.abs(minor)
        val sb = StringBuilder()
        if (neg) sb.append('-')
        sb.append('¥')
        sb.append(v / 100)
        sb.append('.')
        sb.append((v % 100).toString().padStart(2, '0'))
        return sb.toString()
    }
}
