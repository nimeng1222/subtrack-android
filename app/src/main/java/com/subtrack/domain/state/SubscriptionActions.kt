package com.subtrack.domain.state

import com.subtrack.domain.cycle.CycleEngine
import com.subtrack.domain.cycle.RenewalEngine
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.repository.PaymentRecord
import java.time.LocalDate
import java.util.UUID

/**
 * 订阅状态流转的唯一入口。
 *
 * 之所以集中：AC-20 要求"任一状态变更在列表、首页、统计三处一次性同步"，
 * 如果每个页面各自 copy 一份 Subscription 再 upsert，迟早会出现某处忘了改
 * canceledAt / nextChargeDate 的漏改。这里每个动作都返回一个完整的 [Outcome]，
 * 调用方只负责把它写库 + 重排提醒。
 */
object SubscriptionActions {

    /** 一次操作产生的全部副作用：新订阅态、要写的流水、给用户的提示。 */
    data class Outcome(
        val subscription: Subscription,
        val payments: List<PaymentRecord> = emptyList(),
        /** 需要给用户看的提示文案，null 表示按默认静默处理。 */
        val toast: String? = null,
        /**
         * 用户点了"明天再说"：落库方（AppContainer.apply）要据此创建一条
         * 可恢复、可撤销的明日提醒计划。不能只靠 mutedUntil——逾期超过 7 天时
         * 规划器不会再生成任何补发节点，明日提醒必须由这条持久计划承载。
         */
        val snoozeRequested: Boolean = false,
        val expected: Subscription? = null,
    )

    /**
     * AC-11 / AC-12：标记已续费。
     * 会补记多期（每期一条流水），并提示"已推进 N 期"。
     *
     * 试用中的订阅：待确认期次的锚点是试用结束日（不是 nextChargeDate，两者可能不同），
     * 确认即视为转正——写完流水后必须落成 ACTIVE 并清掉 trialEndDate，
     * 否则 DisplayStateResolver 会继续拿旧 trialEndDate 判"待确认"，可以无限重复推进。
     */
    fun confirmRenewal(sub: Subscription, today: LocalDate): Outcome {
        val from = when {
            sub.status == SubscriptionStatus.TRIAL -> sub.trialEndDate ?: sub.nextChargeDate ?: sub.anchorDate
            else -> sub.nextChargeDate ?: sub.anchorDate
        }
        val result = RenewalEngine.confirm(
            anchor = sub.anchorDate,
            type = sub.cycle,
            customDays = sub.customCycleDays,
            fromChargeDate = from,
            today = today,
        )
        val payments = result.credits.map { date ->
            PaymentRecord(
                id = UUID.randomUUID().toString(),
                subscriptionId = sub.id,
                paidAt = date,
                amountMinor = sub.priceMinor,
                currency = sub.currency,
            )
        }
        val updated = sub.copy(
            nextChargeDate = result.nextChargeDate,
            status = when {
                // 一次性买断确认后就走完生命周期（AC-51：与取消等价，仅标签不同）
                sub.cycle == com.subtrack.domain.model.BillingCycleType.ONE_TIME ->
                    SubscriptionStatus.EXPIRED
                // 待确认试用确认续费 = 转正：清掉试用结束日，否则永远派生为"待确认"
                sub.status == SubscriptionStatus.TRIAL -> SubscriptionStatus.ACTIVE
                else -> sub.status
            },
            trialEndDate = if (sub.status == SubscriptionStatus.TRIAL) null else sub.trialEndDate,
        )
        return Outcome(
            expected = sub,
            subscription = updated,
            payments = payments,
            toast = if (result.advancedMultiple) "已推进 ${result.periodsAdvanced} 期" else null,
        )
    }

    /** AC-13：标记已取消。保留历史，退出统计与提醒。 */
    fun cancel(sub: Subscription, today: LocalDate): Outcome = Outcome(
        expected = sub,
        subscription = sub.copy(status = SubscriptionStatus.CANCELED, canceledAt = today),
        toast = "已取消，历史流水仍保留",
    )

    /**
     * AC-15：暂停后恢复必须让用户确认新的下次到期日，系统不得擅自猜测。
     * 所以这里接收调用方算好/用户选定的日期，而不是自己算。
     */
    fun resume(sub: Subscription, newNextChargeDate: LocalDate): Outcome = Outcome(
        expected = sub,
        subscription = sub.copy(
            status = SubscriptionStatus.ACTIVE,
            nextChargeDate = newNextChargeDate,
            canceledAt = null,
        ),
        toast = "已恢复",
    )

    /** 恢复时给一个建议日期：推送过今天的那一期，作为对话框默认值。 */
    fun suggestedResumeDate(sub: Subscription, today: LocalDate): LocalDate =
        RenewalEngine.advanceTo(
            anchor = sub.anchorDate,
            type = sub.cycle,
            customDays = sub.customCycleDays,
            from = today.minusDays(1),
            today = today,
        ) ?: today

    /** AC-14：暂停。停提醒、退出月均，但保留在台账里。 */
    fun pause(sub: Subscription): Outcome = Outcome(
        expected = sub,
        subscription = sub.copy(status = SubscriptionStatus.PAUSED),
        toast = "已暂停，不再提醒也不再计入月均",
    )

    /** AC-50：试用转正。转 ACTIVE 并按周期滚动，同时写一笔流水。 */
    fun convertTrial(sub: Subscription, today: LocalDate): Outcome {
        val chargeDate = sub.trialEndDate ?: sub.nextChargeDate ?: today
        val next = CycleEngine.nextOccurrenceAfter(
            sub.anchorDate, sub.cycle, sub.customCycleDays, chargeDate,
        ) ?: chargeDate.plusMonths(1)
        return Outcome(
            expected = sub,
            subscription = sub.copy(
                status = SubscriptionStatus.ACTIVE,
                nextChargeDate = next,
                trialEndDate = null,
            ),
            payments = listOf(
                PaymentRecord(
                    id = UUID.randomUUID().toString(),
                    subscriptionId = sub.id,
                    paidAt = chargeDate,
                    amountMinor = sub.priceMinor,
                    currency = sub.currency,
                ),
            ),
            toast = "已转正",
        )
    }

    /** AC-51：标记已过期（自然生命周期走完），与取消等价但标签不同。 */
    fun expire(sub: Subscription, today: LocalDate): Outcome = Outcome(
        expected = sub,
        subscription = sub.copy(status = SubscriptionStatus.EXPIRED),
        toast = "已标记为过期",
    )

    /**
     * AC-27b：不再提醒只静音本期，不等于取消订阅。
     *
     * mutedUntil 语义 = "这一期的扣费日"（规划器据此把该期全部补发一并静音：
     * mutedUntil >= 扣费日 即整期静音），不是"静音到某天为止"。
     * 旧的 dueDate+1 写法只会压住第 1 天，第 3/7 天补发照发——这就是漏改的根因。
     */
    fun muteThisCycle(sub: Subscription, dueDate: LocalDate): Outcome = Outcome(
        expected = sub,
        subscription = sub.copy(mutedUntil = dueDate),
        toast = "本期不再提醒，但仍计入统计",
    )

    /** AC-27a：明天再说。不改到期日、不改状态、不改统计；明日计划由持久提醒承载。 */
    fun snooze(sub: Subscription, today: LocalDate): Outcome = Outcome(
        expected = sub,
        subscription = sub.copy(mutedUntil = null),
        snoozeRequested = true,
        toast = "明天再提醒你",
    )

    /** 删除后撤销：只把软删除标记清掉。 */
    fun restore(sub: Subscription): Outcome = Outcome(
        expected = sub,
        subscription = sub.copy(deletedAtUtcMillis = null),
        toast = "已撤销删除",
    )
}
