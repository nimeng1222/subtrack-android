package com.subtrack.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.subtrack.SubTrackApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Period-bound actions; transactional validation and mutation live in NotificationActionHandler. */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SubTrackApp ?: return
        val container = app.container
        val subscriptionId = intent.getStringExtra(AlarmReminderScheduler.EXTRA_SUBSCRIPTION_ID) ?: return
        val notificationId = intent.getIntExtra(AlarmReminderScheduler.EXTRA_NOTIFICATION_ID, 0)
        val action = intent.action ?: return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.awaitSettings()
                val chargeDay = if (intent.hasExtra(AlarmReminderScheduler.EXTRA_CHARGE_DATE))
                    intent.getIntExtra(AlarmReminderScheduler.EXTRA_CHARGE_DATE, 0) else null
                val requested = when (action) {
                    ACTION_CONFIRM -> NotificationAction.CONFIRM
                    ACTION_SNOOZE -> NotificationAction.SNOOZE
                    ACTION_MUTE -> NotificationAction.MUTE
                    else -> null
                }
                if (requested != null) NotificationActionHandler(container.repository, container.clock) { outcome ->
                    container.repository.get(outcome.subscription.id)?.let { current ->
                        container.reminderService.replan(current)
                        if (outcome.snoozeRequested && current == outcome.subscription) container.reminderService.scheduleSnoozeTomorrow(current)
                    }
                }.handle(subscriptionId, chargeDay, requested)
                if (notificationId != 0) NotificationManagerCompat.from(context).cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM = "com.subtrack.action.CONFIRM_CHARGE"
        const val ACTION_SNOOZE = "com.subtrack.action.SNOOZE_1D"
        const val ACTION_MUTE = "com.subtrack.action.MUTE_REMINDER"
    }
}
