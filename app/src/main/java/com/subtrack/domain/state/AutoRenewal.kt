package com.subtrack.domain.state

import com.subtrack.domain.cycle.RenewalEngine
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.repository.PaymentRecord
import java.time.LocalDate
import java.util.UUID

/**
 * 自动续订：订阅存续即视为已付（1.9.7 的默认模型）。
 *
 * 用户不需要在到期日手动点“已续费/已支付”。应用启动与每日扫描时，对满足下面条件的条目
 * 直接按当期记一笔流水并把下次扣费推进到下一期，口径与手动确认完全一致（同一个
 * [RenewalEngine.confirm]）：
 *
 * - 状态是 ACTIVE（跳过 TRIAL / PAUSED / CANCELED / EXPIRED）；
 * - 用户没有关掉自动续订（[Subscription.autoRenew]）；
 * - 下次扣费日不晚于今天（到当天即算已扣）；
 * - 不是一次性买断（ONE_TIME 没有“下一期”，仍由用户手动确认）。
 *
 * 幂等有两层：
 * 1. [paidDates] 传入库里已有的流水日期，已记过的期次不再重复写；
 * 2. `payment_record` 的 `(subscriptionId, paidAtEpochDay)` 唯一索引兜底。
 * 推进日期后下次运行不再命中，因此重复调用不会重复记账。
 */
object AutoRenewal {

    fun plan(
        sub: Subscription,
        today: LocalDate,
        paidDates: Set<LocalDate> = emptySet(),
    ): SubscriptionActions.Outcome? {
        if (sub.isDeleted) return null
        if (sub.status != SubscriptionStatus.ACTIVE) return null
        if (!sub.autoRenew) return null
        if (sub.cycle == BillingCycleType.ONE_TIME) return null
        val from = sub.nextChargeDate ?: return null
        // 到了扣费日就记，包括当天；未来日期一律不动。
        if (from.isAfter(today)) return null

        val result = RenewalEngine.confirm(
            anchor = sub.anchorDate,
            type = sub.cycle,
            customDays = sub.customCycleDays,
            fromChargeDate = from,
            today = today,
        )
        // RenewalEngine 的追平条件是 next < today；若下一期正好是今天，这一期同样到期。
        // 这里一并记掉并把日期推到严格晚于今天，避免同一天第二次启动时再补一笔。
        val credits = result.credits.toMutableList()
        var next = result.nextChargeDate
        if (next != null && !next.isAfter(today)) {
            credits += next
            next = com.subtrack.domain.cycle.CycleEngine.nextOccurrenceAfter(
                sub.anchorDate, sub.cycle, sub.customCycleDays, today,
            )
        }
        val payments = credits
            .filterNot { it in paidDates }
            .map { date ->
                PaymentRecord(
                    id = UUID.randomUUID().toString(),
                    subscriptionId = sub.id,
                    paidAt = date,
                    amountMinor = sub.priceMinor,
                    currency = sub.currency,
                )
            }
        return SubscriptionActions.Outcome(
            expected = sub,
            subscription = sub.copy(nextChargeDate = next),
            payments = payments,
        )
    }
}
