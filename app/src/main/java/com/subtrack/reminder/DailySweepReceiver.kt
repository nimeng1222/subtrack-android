package com.subtrack.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.subtrack.SubTrackApp
import com.subtrack.applyAutoRenewals
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
                // 先补自动续订，再重排提醒：否则已到期的条目会被排成“待确认”提醒。
                runCatching { app.container.applyAutoRenewals() }
                    .onFailure { android.util.Log.e("SubTrackAutoRenew", "每日扫描补记失败", it) }
                app.container.reminderService.sweep()
            } finally {
                pending.finish()
            }
        }
    }
}
