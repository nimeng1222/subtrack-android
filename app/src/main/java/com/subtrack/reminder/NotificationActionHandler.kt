package com.subtrack.reminder

import com.subtrack.core.time.AppClock
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.repository.SubscriptionRepository
import com.subtrack.domain.state.SubscriptionActions
import java.time.LocalDate

fun Subscription.currentChargeDate(): LocalDate? =
    if (status == SubscriptionStatus.TRIAL) trialEndDate ?: nextChargeDate else nextChargeDate

enum class NotificationAction { CONFIRM, SNOOZE, MUTE }

/** Android receiver and Room tests share this exact transaction. Re-read and validate inside it. */
class NotificationActionHandler(
    private val repository: SubscriptionRepository,
    private val clock: AppClock,
    /** 需要提示用户但本次没有落库时调用（例如提前通知上点“已续费”）。 */
    private val onNotice: suspend (String) -> Unit = {},
    private val afterCommit: suspend (SubscriptionActions.Outcome) -> Unit,
) {
    suspend fun handle(id: String, chargeDay: Int?, action: NotificationAction): Boolean {
        if (chargeDay == null || id == ReminderService.TEST_SUBSCRIPTION_ID) return false
        var notice: String? = null
        val result = repository.runInTransaction {
            val sub = repository.get(id) ?: return@runInTransaction null
            if (sub.isDeleted || sub.status !in listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL) ||
                sub.currentChargeDate()?.toEpochDay() != chargeDay.toLong()) return@runInTransaction null
            val due = sub.currentChargeDate()!!
            if (repository.paymentsOf(id).any { it.paidAt == due }) return@runInTransaction null
            val today = clock.today()
            // Same current-period rule as the detail page: an advance notification is not due yet,
            // so CONFIRM must not claim success or write an empty payment/upsert.
            if (action == NotificationAction.CONFIRM && !SubscriptionActions.isRenewalDue(sub, today)) {
                notice = SubscriptionActions.NOT_DUE_CONFIRM_MESSAGE
                return@runInTransaction null
            }
            val outcome = when (action) {
                NotificationAction.CONFIRM -> SubscriptionActions.confirmRenewal(sub, today)
                NotificationAction.SNOOZE -> SubscriptionActions.snooze(sub, today)
                NotificationAction.MUTE -> SubscriptionActions.muteThisCycle(sub, due)
            }
            repository.recordPayments(outcome.payments)
            repository.upsert(outcome.subscription)
            outcome
        }
        if (result == null) {
            notice?.let { onNotice(it) }
            return false
        }
        afterCommit(result)
        return true
    }
}
