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
    private val afterCommit: suspend (SubscriptionActions.Outcome) -> Unit,
) {
    suspend fun handle(id: String, chargeDay: Int?, action: NotificationAction): Boolean {
        if (chargeDay == null || id == ReminderService.TEST_SUBSCRIPTION_ID) return false
        val result = repository.runInTransaction {
            val sub = repository.get(id) ?: return@runInTransaction null
            if (sub.isDeleted || sub.status !in listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL) ||
                sub.currentChargeDate()?.toEpochDay() != chargeDay.toLong()) return@runInTransaction null
            val due = sub.currentChargeDate()!!
            // Same current-period confirmation as the app UI, including an advance notification.
            if (repository.paymentsOf(id).any { it.paidAt == due }) return@runInTransaction null
            val outcome = when (action) {
                NotificationAction.CONFIRM -> SubscriptionActions.confirmRenewal(sub, clock.today())
                NotificationAction.SNOOZE -> SubscriptionActions.snooze(sub, clock.today())
                NotificationAction.MUTE -> SubscriptionActions.muteThisCycle(sub, due)
            }
            repository.recordPayments(outcome.payments)
            repository.upsert(outcome.subscription)
            outcome
        } ?: return false
        afterCommit(result)
        return true
    }
}
