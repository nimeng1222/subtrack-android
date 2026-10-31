package com.subtrack.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.subtrack.SubTrackApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DailySweepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SubTrackApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = app.container.awaitSettings()
                DailySweepScheduler.schedule(context, settings.reminderTime)
                app.container.reminderService.sweep()
            } finally {
                pending.finish()
            }
        }
    }
}
