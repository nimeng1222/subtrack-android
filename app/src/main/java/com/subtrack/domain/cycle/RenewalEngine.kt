package com.subtrack.domain.cycle

import com.subtrack.domain.model.BillingCycleType
import java.time.LocalDate

/**
 * 续费推进。AC-12 要求：若推进后仍早于今天，反复推进到第一个不早于今天的日期，
 * 并告知"已推进 N 期"。
 *
 * 之所以单列一个类而不是塞进 CycleEngine：CycleEngine 只回答"下一个扣费日是哪天"，
 * 这里回答的是"用户补记了多期之后，账应该滚到哪里、滚了几期"——它是一条业务规则，
 * 需要被单独测试。
 */
object RenewalEngine {

    data class Result(
        val nextChargeDate: LocalDate?,
        val credits: List<LocalDate>,
        /** 实际推进的期数（>=1）。一次性订阅为 0。 */
        val periodsAdvanced: Int,
    ) {
        val advancedMultiple: Boolean get() = periodsAdvanced > 1
    }

    /**
     * 从 [fromChargeDate] 这一期开始确认续费：
     * - 记一条该期的流水（[credits] 的第一笔）；
     * - nextChargeDate 推进到严格晚于占位的日期；
     * - 若仍早于 [today]，继续推进（补记多期），每一期都算一次已完成的扣费。
     *
     * 一次性订阅不滚动：确认一次后 nextChargeDate 置空，periodsAdvanced = 0。
     */
    fun confirm(
        anchor: LocalDate,
        type: BillingCycleType,
        customDays: Int?,
        fromChargeDate: LocalDate,
        today: LocalDate,
    ): Result {
        if (type == BillingCycleType.ONE_TIME) {
            return Result(nextChargeDate = null, credits = listOf(fromChargeDate), periodsAdvanced = 0)
        }

        val credits = mutableListOf(fromChargeDate)
        var next = CycleEngine.nextOccurrenceAfter(anchor, type, customDays, fromChargeDate)
        var guard = 0
        // 补记：把用户长期没打开 App 期间漏掉的期数一次性滚过去
        while (next != null && next.isBefore(today) && guard++ < MAX_CATCH_UP) {
            credits += next
            next = CycleEngine.nextOccurrenceAfter(anchor, type, customDays, next)
        }
        return Result(nextChargeDate = next, credits = credits, periodsAdvanced = credits.size)
    }

    /** 只推进日期、不记流水（用于"跳过本期"这类不花钱的场景）。 */
    fun advanceTo(
        anchor: LocalDate,
        type: BillingCycleType,
        customDays: Int?,
        from: LocalDate,
        today: LocalDate,
    ): LocalDate? {
        if (type == BillingCycleType.ONE_TIME) return null
        var next = CycleEngine.nextOccurrenceAfter(anchor, type, customDays, from)
        var guard = 0
        while (next != null && next.isBefore(today) && guard++ < MAX_CATCH_UP) {
            next = CycleEngine.nextOccurrenceAfter(anchor, type, customDays, next)
        }
        return next
    }

    /** 防御性上限：一天一期也要 10 年才会撞到，防止脏数据把主线程卡死。 */
    const val MAX_CATCH_UP = 480
}
