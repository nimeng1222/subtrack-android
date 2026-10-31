package com.subtrack.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.subtrack.SubTrackApp
import kotlinx.coroutines.*

/** Both modern and upgrade-era intents enter the persisted, validated delivery path. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SubTrackApp ?: return
        val id = intent.getStringExtra(AlarmReminderScheduler.EXTRA_SUBSCRIPTION_ID) ?: return
        val rowId = intent.getLongExtra(AlarmReminderScheduler.EXTRA_REMINDER_ROW_ID, -1L)
        val day = if (intent.hasExtra(AlarmReminderScheduler.EXTRA_CHARGE_DATE))
            intent.getIntExtra(AlarmReminderScheduler.EXTRA_CHARGE_DATE, 0) else null
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.awaitSettings()
                if (rowId > 0) app.container.reminderService.deliverExact(rowId)
                else if (day != null) app.container.reminderService.deliverLegacy(id, day)
            } finally { pending.finish() }
        }
    }
}
