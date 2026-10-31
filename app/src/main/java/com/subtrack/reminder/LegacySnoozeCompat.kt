package com.subtrack.reminder

import com.subtrack.data.db.ScheduledReminderEntity
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import java.time.Instant
import java.time.ZoneId

/** v1.3.2 wrote the snooze request date into mutedUntil, but explicit mute wrote the charge date.
 * Only convert an unambiguous, still-pending request for the current overdue period. When the
 * request and charge date coincide we cannot recover intent: keep mute, rather than undo a later mute.
 */
internal fun hasLegacySnoozeMute(
    sub: Subscription,
    rows: List<ScheduledReminderEntity>,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val charge = sub.currentChargeDate() ?: return false
    val muted = sub.mutedUntil ?: return false
    if (sub.isDeleted || sub.status !in listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL) || muted <= charge) return false
    return rows.any { row ->
        row.kind == "SNOOZE" && row.state == "PENDING" && row.subscriptionId == sub.id &&
            row.chargeDateEpochDay.toLong() == charge.toEpochDay() &&
            row.createdAtUtcMillis <= nowMillis && row.createdAtUtcMillis < row.fireAtUtcMillis &&
            Instant.ofEpochMilli(row.createdAtUtcMillis).atZone(zone).toLocalDate() == muted &&
            Instant.ofEpochMilli(row.fireAtUtcMillis).atZone(zone).toLocalDate() == muted.plusDays(1)
    }
}
