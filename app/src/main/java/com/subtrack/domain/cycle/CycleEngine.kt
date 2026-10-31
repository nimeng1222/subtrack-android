package com.subtrack.domain.cycle

import com.subtrack.domain.model.BillingCycleType
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 周期推进的唯一实现。
 *
 * 铁律：所有分支都从 anchor 重算，绝不用“上一次结果”做基数。
 * 1 月 31 日的月付，2 月退到 28 日，3 月必须回到 31 日；从上一次累加会永久漂移。
 */
object CycleEngine {

    private const val MAX_OCCURRENCES_PER_WINDOW = 400

    /** 返回 >= from 的第一个扣费日；ONE_TIME 已过则返回 null。 */
    fun occurrenceAtOrAfter(
        anchor: LocalDate,
        type: BillingCycleType,
        customDays: Int?,
        from: LocalDate,
    ): LocalDate? = when (type) {
        BillingCycleType.WEEKLY -> anchor.plusWeeks(stepsOfDays(anchor, from, 7))
        BillingCycleType.BIWEEKLY -> anchor.plusWeeks(stepsOfDays(anchor, from, 14) * 2)
        BillingCycleType.FOUR_WEEKLY -> anchor.plusWeeks(stepsOfDays(anchor, from, 28) * 4)
        BillingCycleType.MONTHLY -> anchoredMonths(anchor, from, 1)
        BillingCycleType.QUARTERLY -> anchoredMonths(anchor, from, 3)
        BillingCycleType.SEMIANNUAL -> anchoredMonths(anchor, from, 6)
        BillingCycleType.YEARLY -> anchoredMonths(anchor, from, 12)
        BillingCycleType.CUSTOM_DAYS -> {
            val n = requireNotNull(customDays) { "CUSTOM_DAYS 必须提供 customCycleDays" }
            require(n in 1..3650) { "customCycleDays 超出 1..3650：$n" }
            anchor.plusDays(stepsOfDays(anchor, from, n.toLong()) * n)
        }
        BillingCycleType.ONE_TIME -> if (!anchor.isBefore(from)) anchor else null
    }

    /** 返回严格晚于 [after] 的第一个扣费日。已续费后推进下一期必须用这个。 */
    fun nextOccurrenceAfter(
        anchor: LocalDate,
        type: BillingCycleType,
        customDays: Int?,
        after: LocalDate,
    ): LocalDate? = occurrenceAtOrAfter(anchor, type, customDays, after.plusDays(1))

    /**
     * 返回严格早于 [before] 的最近一个扣费日。用于账单日历向前回溯历史。
     *
     * 实现思路：先把 [before] 退一天得到候选上界，再用 occurrenceAtOrAfter 从锚点起算，
     * 这样月末钳制（31 号在小月变 30/28）等规则只有一份实现，不会两处写歪。
     */
    fun previousOccurrenceBefore(
        anchor: LocalDate,
        type: BillingCycleType,
        customDays: Int?,
        before: LocalDate,
    ): LocalDate? {
        if (type == BillingCycleType.ONE_TIME) return null
        if (!before.isAfter(anchor)) return null

        // 逐期推进，记录"最后一个严格早于 before"的那期。
        // 注意 anchor 本身也可能满足条件（anchor < before），所以从 anchor 开始算候补。
        var cursor = anchor
        var last: LocalDate? = if (anchor.isBefore(before)) anchor else null
        var guard = 0
        while (guard++ < 5000) {
            val next = nextOccurrenceAfter(anchor, type, customDays, cursor) ?: return last
            if (!next.isBefore(before)) return last
            last = next
            cursor = next
        }
        return last
    }

    /** 返回 [windowStart, windowEnd] 内的全部扣费日，升序。 */
    fun occurrencesBetween(
        anchor: LocalDate,
        type: BillingCycleType,
        customDays: Int?,
        windowStart: LocalDate,
        windowEnd: LocalDate,
    ): List<LocalDate> {
        if (windowEnd.isBefore(windowStart)) return emptyList()
        val out = ArrayList<LocalDate>()
        var cursor = occurrenceAtOrAfter(anchor, type, customDays, windowStart) ?: return emptyList()
        var guard = 0
        while (!cursor.isAfter(windowEnd)) {
            out += cursor
            if (++guard > MAX_OCCURRENCES_PER_WINDOW) break
            cursor = occurrenceAtOrAfter(anchor, type, customDays, cursor.plusDays(1)) ?: break
        }
        return out
    }

    /** 从 anchor 推进 n 期后的日期，供“已续费 N 期”与测试使用。 */
    fun plusPeriods(
        anchor: LocalDate,
        type: BillingCycleType,
        customDays: Int?,
        periods: Int,
    ): LocalDate? {
        if (periods < 0) return null
        return when (type) {
            BillingCycleType.WEEKLY -> anchor.plusWeeks(periods.toLong())
            BillingCycleType.BIWEEKLY -> anchor.plusWeeks(periods * 2L)
            BillingCycleType.FOUR_WEEKLY -> anchor.plusWeeks(periods * 4L)
            BillingCycleType.MONTHLY -> anchoredAt(anchor, anchor.year * 12 + anchor.monthValue - 1 + periods)
            BillingCycleType.QUARTERLY -> anchoredAt(anchor, anchor.year * 12 + anchor.monthValue - 1 + periods * 3)
            BillingCycleType.SEMIANNUAL -> anchoredAt(anchor, anchor.year * 12 + anchor.monthValue - 1 + periods * 6)
            BillingCycleType.YEARLY -> anchoredAt(anchor, anchor.year * 12 + anchor.monthValue - 1 + periods * 12)
            BillingCycleType.CUSTOM_DAYS -> {
                val n = requireNotNull(customDays) { "CUSTOM_DAYS 必须提供 customCycleDays" }
                anchor.plusDays(periods.toLong() * n)
            }
            BillingCycleType.ONE_TIME -> if (periods == 0) anchor else null
        }
    }

    /** ceil((from - anchor) / step)，夹到 >= 0。 */
    private fun stepsOfDays(anchor: LocalDate, from: LocalDate, step: Long): Long {
        val raw = ChronoUnit.DAYS.between(anchor, from)
        if (raw <= 0L) return 0L
        return (raw + step - 1) / step
    }

    /** 按“锚点月 + k*step 个月”推进，日号取 min(anchor.dayOfMonth, 该月天数)。 */
    private fun anchoredMonths(anchor: LocalDate, from: LocalDate, monthsPerStep: Int): LocalDate {
        val anchorMonth = anchor.year * 12 + anchor.monthValue - 1
        val fromMonth = from.year * 12 + from.monthValue - 1
        var k = if (fromMonth <= anchorMonth) 0 else (fromMonth - anchorMonth + monthsPerStep - 1) / monthsPerStep
        while (true) {
            val candidate = anchoredAt(anchor, anchorMonth + k * monthsPerStep)
            if (!candidate.isBefore(from)) return candidate
            k++
        }
    }

    /** 给定 0-based 月序号，返回把 anchor 日号钳制到该月长度后的日期。 */
    private fun anchoredAt(anchor: LocalDate, monthIndex0Based: Int): LocalDate {
        val year = monthIndex0Based / 12
        val month = monthIndex0Based % 12 + 1
        val day = minOf(anchor.dayOfMonth, YearMonth.of(year, month).lengthOfMonth())
        return LocalDate.of(year, month, day)
    }
}
