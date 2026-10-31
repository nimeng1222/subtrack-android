package com.subtrack.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.subtrack.SubTrackApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 重启 / 更新 / 授权变化 / 改时区后：重排全部提醒 + 重新挂上每日扫描。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SubTrackApp ?: return
        NotificationHelper.createChannels(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = app.container.awaitSettings()
                DailySweepScheduler.schedule(context, settings.reminderTime)
                // 恢复有效期次：七天补投窗口，同期每天最多一条；超窗节点显式过期。
                app.container.reminderService.sweep()
            } finally {
                pending.finish()
            }
        }
    }
}
