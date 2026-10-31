package com.subtrack.domain.stats

import com.subtrack.core.money.sumMoney
import com.subtrack.domain.cycle.CycleEngine
import com.subtrack.domain.model.Subscription
import java.time.LocalDate
import java.time.YearMonth

/**
 * 账单日历（v1.1）：把一年里的每一天映射成"当天要扣多少钱、扣哪几笔"。
 *
 * 为什么单独一个类而不是塞进 StatsEngine：
 * StatsEngine 回答"总共多少"，日历回答"哪一天"，两者的数据形状完全不同
 * （前者是标量，后者是按日分桶的稀疏表）。混在一起会让两边都难读。
 *
 * 复用的是 [CycleEngine.occurrencesBetween] —— 日历不自己算日期，
 * 否则月末锚点（31 号在小月钳制到月末）这条规则会出现第二份实现。
 */
object BillingCalendar {

    /** 一年内某一天的账单。 */
    data class DayBill(
        val date: LocalDate,
        val subscriptions: List<Subscription>,
    ) {
        val totalMinor: Long get() = subscriptions.sumMoney { it.priceMinor }
        val count: Int get() = subscriptions.size
    }

    /**
     * 生成 [year] 全年的按日账单，只包含**有扣费**的日期（稀疏表）。
     *
     * @param projected true 表示按当前订阅预测未来（未来日期也能算），
     *   false 表示只看已过去的日子——两者实际上是同一套推算，
     *   区别只在调用方怎么用，所以这里不区分。
     */
    fun year(
        subs: List<Subscription>,
        year: Int,
    ): Map<LocalDate, DayBill> {
        val start = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year, 12, 31)
        return range(subs, start, end)
    }

    fun month(
        subs: List<Subscription>,
        yearMonth: YearMonth,
    ): Map<LocalDate, DayBill> = range(subs, yearMonth.atDay(1), yearMonth.atEndOfMonth())

    private fun range(
        subs: List<Subscription>,
        start: LocalDate,
        end: LocalDate,
    ): Map<LocalDate, DayBill> {
        val buckets = LinkedHashMap<LocalDate, MutableList<Subscription>>()

        subs.filter { it.isForecastIncluded }.forEach { sub ->
            // 一次性买断不进入"未来扣费"的日历，它只在买断当天算一次
            if (sub.cycle == com.subtrack.domain.model.BillingCycleType.ONE_TIME) {
                val d = sub.nextChargeDate ?: sub.anchorDate
                if (!d.isBefore(start) && !d.isAfter(end)) {
                    buckets.getOrPut(d) { mutableListOf() } += sub
                }
                return@forEach
            }

            // 用 nextChargeDate 作为"游标"向前后展开，而不是从 anchor 起算。
            //
            // 为什么不从 anchor 起算：anchor 只定义"几号扣"这个模式，用户随时可以手动
            // 改下次到期日（编辑页允许），此时 anchor 与真实扣费日会脱节。
            // 若从 anchor 推，日历会显示 26 号，而列表显示 24 号——同一条订阅两处不一致。
            // 所以以真实游标为基准，向前回溯到窗口起点、向后推进到窗口终点。
            val cursor = sub.nextChargeDate ?: sub.anchorDate
            val dates = ArrayList<LocalDate>()

            // 向后：从游标推进到窗口结束
            var d: LocalDate? = cursor
            var guard = 0
            while (d != null && !d.isAfter(end) && guard++ < MAX_STEPS) {
                dates += d
                d = CycleEngine.nextOccurrenceAfter(
                    sub.anchorDate, sub.cycle, sub.customCycleDays, d,
                )
            }

            // 向前：从游标回溯到窗口开始（历史月份要看得到）。
            //
            // 关键约束：回溯**必须止步于 anchor**——订阅在锚点之前根本不存在，
            // 把那段算进来会凭空给过去几个月造出扣费（趋势图会出现"订阅还没买就有支出"）。
            var back = CycleEngine.previousOccurrenceBefore(
                sub.anchorDate, sub.cycle, sub.customCycleDays, cursor,
            )
            guard = 0
            while (back != null && !back.isBefore(start) && !back.isBefore(sub.anchorDate) &&
                guard++ < MAX_STEPS
            ) {
                dates += back
                back = CycleEngine.previousOccurrenceBefore(
                    sub.anchorDate, sub.cycle, sub.customCycleDays, back,
                )
            }

            // 统一按窗口过滤 + 去重。
            // 不能依赖各分支各自判断边界：向前回溯时很容易漏判上界，
            // 结果 month(2026-05) 把 9 月、10 月的账单也返回了（真实踩过）。
            dates.asSequence()
                .filter { !it.isBefore(start) && !it.isAfter(end) }
                .distinct()
                .forEach { date -> buckets.getOrPut(date) { mutableListOf() } += sub }
        }

        return buckets
            .toSortedMap()
            .mapValues { (date, list) -> DayBill(date, list.sortedBy { it.name }) }
    }

    /** 防御：一天一期的订阅在 5 年窗口内也只有 ~1826 次，超过就当脏数据。 */
    private const val MAX_STEPS = 600

    /** 12 个月各自的总支出（用于日历下方的月度条与趋势图）。 */
    fun monthlyTotals(subs: List<Subscription>, year: Int): List<Long> {
        val byDay = year(subs, year)
        return (1..12).map { m ->
            byDay.entries.filter { it.key.monthValue == m }.sumMoney { it.value.totalMinor }
        }
    }

    /** 近 [months] 个月的逐月支出，升序（最早的在前）。用于趋势图。 */
    fun recentMonths(
        subs: List<Subscription>,
        today: LocalDate,
        months: Int = 6,
    ): List<MonthTotal> {
        val list = ArrayList<MonthTotal>(months)
        val current = YearMonth.of(today.year, today.monthValue)
        for (i in months - 1 downTo 0) {
            val ym = current.minusMonths(i.toLong())
            val bills = month(subs, ym)
            list += MonthTotal(
                yearMonth = ym,
                totalMinor = bills.values.sumMoney { it.totalMinor },
                count = bills.values.sumOf { it.count },
            )
        }
        return list
    }

    data class MonthTotal(val yearMonth: YearMonth, val totalMinor: Long, val count: Int)

    /**
     * 一天的热度等级 0..4，用于日历格子着色。
     *
     * 用分位数而不是绝对金额：用户订阅单价跨度可能从 6 元到 999 元，
     * 按绝对金额会把大多数格子都归到最低档，热力图就没信息量了。
     */
    fun heatLevel(totalMinor: Long, maxMinor: Long): Int {
        if (totalMinor <= 0L || maxMinor <= 0L) return 0
        val ratio = totalMinor.toDouble() / maxMinor
        return when {
            ratio >= 0.75 -> 4
            ratio >= 0.5 -> 3
            ratio >= 0.25 -> 2
            else -> 1
        }
    }
}
