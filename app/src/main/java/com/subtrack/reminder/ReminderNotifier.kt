package com.subtrack.reminder

import android.content.Context

/**
 * 通知投递的最小端口。ReminderService 只关心"发一条待确认通知"，
 * 不该背着 android Context 才能构造——抽这一层让调度状态机能在 JVM 单测里
 * 用记录型替身验证（2026-10-27 审查建议：必要时引入小范围接口提高可测试性）。
 */
interface ReminderNotifier {
    fun show(
        title: String,
        body: String,
        subscriptionId: String,
        chargeDateEpochDay: Int,
        notificationId: Int,
    )
}

/** 生产实现：转发到 NotificationHelper（含 POST_NOTIFICATIONS 权限检查与 runCatching 兜底）。 */
class NotificationNotifier(private val context: Context) : ReminderNotifier {
    override fun show(
        title: String,
        body: String,
        subscriptionId: String,
        chargeDateEpochDay: Int,
        notificationId: Int,
    ) {
        NotificationHelper.show(context, title, body, subscriptionId, chargeDateEpochDay, notificationId)
    }
}
