package com.subtrack.domain.state

import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 展示态 = 持久状态 + 时间派生。只用于 UI 与提醒判定，绝不写回数据库。 */
sealed interface SubscriptionDisplayState {
    data object Active : SubscriptionDisplayState
    data object Trial : SubscriptionDisplayState
    data object Paused : SubscriptionDisplayState
    data object Canceled : SubscriptionDisplayState
    data object Expired : SubscriptionDisplayState

    /** 到期日已过、该日没有流水、且用户还没处理。永久保留，不因超过 N 天而消失。 */
    data class PendingConfirm(val overdueDays: Int, val overdueDate: LocalDate) : SubscriptionDisplayState
}

object DisplayStateResolver {

    fun resolve(
        sub: Subscription,
        today: LocalDate,
        hasPaymentOnDueDate: Boolean,
    ): SubscriptionDisplayState {
        when (sub.status) {
            SubscriptionStatus.PAUSED -> return SubscriptionDisplayState.Paused
            SubscriptionStatus.CANCELED -> return SubscriptionDisplayState.Canceled
            SubscriptionStatus.EXPIRED -> return SubscriptionDisplayState.Expired
            else -> Unit
        }

        if (sub.status == SubscriptionStatus.TRIAL) {
            val trialEnd = sub.trialEndDate
            if (trialEnd != null && trialEnd.isBefore(today) && !hasPaymentOnDueDate) {
                return SubscriptionDisplayState.PendingConfirm(
                    overdueDays = ChronoUnit.DAYS.between(trialEnd, today).toInt(),
                    overdueDate = trialEnd,
                )
            }
            return SubscriptionDisplayState.Trial
        }

        val next = sub.nextChargeDate ?: return SubscriptionDisplayState.Active
        if (!next.isBefore(today) || hasPaymentOnDueDate) return SubscriptionDisplayState.Active

        return SubscriptionDisplayState.PendingConfirm(
            overdueDays = ChronoUnit.DAYS.between(next, today).toInt(),
            overdueDate = next,
        )
    }

    /** 列表排序权重：待确认置顶，其次 3 天内到期，再按扣费日升序。 */
    fun sortWeight(state: SubscriptionDisplayState): Int = when (state) {
        is SubscriptionDisplayState.PendingConfirm -> 0
        SubscriptionDisplayState.Trial -> 1
        SubscriptionDisplayState.Active -> 2
        SubscriptionDisplayState.Paused -> 3
        SubscriptionDisplayState.Canceled -> 4
        SubscriptionDisplayState.Expired -> 5
    }
}
