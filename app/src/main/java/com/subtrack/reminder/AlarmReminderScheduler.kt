package com.subtrack.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 系统闹钟的调度抽象。抽接口不是过度设计：ReminderService 的重排/撤销逻辑
 * 需要在 JVM 单测里用替身验证（连续重排不积累闹钟、暂停即撤销），不能背着
 * android.app.AlarmManager 跑。
 */
interface ReminderScheduler {
    fun schedule(
        reminderId: Int,
        fireAtMillis: Long,
        title: String,
        body: String,
        subscriptionId: String,
        chargeDateEpochDay: Int,
        notificationId: Int,
        reminderRowId: Long = -1L,
    )

    fun cancel(reminderId: Int)
}

class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {

    override fun schedule(
        reminderId: Int,
        fireAtMillis: Long,
        title: String,
        body: String,
        subscriptionId: String,
        chargeDateEpochDay: Int,
        notificationId: Int,
        reminderRowId: Long,
    ) {
        if (fireAtMillis <= System.currentTimeMillis()) return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
            putExtra(EXTRA_CHARGE_DATE, chargeDateEpochDay)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            // 计划行 id：触发端据此领取（claim）同一条计划，与每日扫描共享幂等状态
            putExtra(EXTRA_REMINDER_ROW_ID, reminderRowId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            reminderId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, pending)
        }
    }

    override fun cancel(reminderId: Int) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            reminderId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
        const val EXTRA_SUBSCRIPTION_ID = "extra_subscription_id"
        const val EXTRA_CHARGE_DATE = "extra_charge_date"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_REMINDER_ROW_ID = "extra_reminder_row_id"
    }
}
