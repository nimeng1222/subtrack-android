package com.subtrack.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.subtrack.MainActivity
import com.subtrack.R

object NotificationHelper {

    const val CHANNEL_DUE = "reminder_due"
    private const val CHANNEL_DIGEST = "reminder_digest"

    fun createChannels(context: Context) {
        // minSdk 26（Android O）起通知渠道为强制 API，无需再判 SDK_INT。
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DUE, "到期提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "到期当天的单条提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, "每日汇总", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "提前 N 天提醒与每日汇总"
            },
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // canPost() 已在函数首行做过权限检查；lint 无法跨函数识别，这里显式声明已处理
    @android.annotation.SuppressLint("MissingPermission")
    fun show(
        context: Context,
        title: String,
        body: String,
        subscriptionId: String,
        chargeDateEpochDay: Int,
        notificationId: Int,
    ) {
        if (!canPost(context)) return

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DUE)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_check, "已续费",
                action(context, ReminderActionReceiver.ACTION_CONFIRM, subscriptionId, chargeDateEpochDay, notificationId, 1),
            )
            .addAction(
                R.drawable.ic_clock, "明天再说",
                action(context, ReminderActionReceiver.ACTION_SNOOZE, subscriptionId, chargeDateEpochDay, notificationId, 2),
            )
            .addAction(
                R.drawable.ic_bell, "不再提醒",
                action(context, ReminderActionReceiver.ACTION_MUTE, subscriptionId, chargeDateEpochDay, notificationId, 3),
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    private fun action(
        context: Context,
        action: String,
        subscriptionId: String,
        chargeDateEpochDay: Int,
        notificationId: Int,
        offset: Int,
    ): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmReminderScheduler.EXTRA_SUBSCRIPTION_ID, subscriptionId)
            putExtra(AlarmReminderScheduler.EXTRA_CHARGE_DATE, chargeDateEpochDay)
            putExtra(AlarmReminderScheduler.EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId * 10 + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
