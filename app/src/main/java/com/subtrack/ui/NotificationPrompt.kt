package com.subtrack.ui

/** 谁在触发通知权限流程：用户主动点"去开启"，还是应用自己（保存订阅后）。 */
internal enum class NotificationPromptMode { USER_INITIATED, AFTER_SAVE }

internal enum class NotificationPromptAction { NONE, REQUEST_PERMISSION, OPEN_SYSTEM_SETTINGS }

/**
 * 通知权限该怎么处理。
 *
 * 这里锁的是一个真实缺陷：改版前"保存订阅"之后无条件调用 openNotificationSettings，
 * 用户在权限弹窗里点了取消、或早就拒绝过，就会被直接踢到系统设置页——每存一条订阅跳一次。
 *
 * 规则：
 * - 已经能发通知 → 什么都不做。
 * - Android 13+ 且系统还愿意弹窗（或这是应用自己的时机）→ 请求权限；用户拒绝就只提示，
 *   绝不替用户跳页。被永久拒绝时系统会静默返回 denied，不会跳转。
 * - 只有**用户主动**点"去开启"、且系统已经不会弹窗时，才打开系统设置。
 */
internal fun notificationPromptAction(
    mode: NotificationPromptMode,
    sdkInt: Int,
    canPost: Boolean,
    rationaleVisible: Boolean,
): NotificationPromptAction = when {
    canPost -> NotificationPromptAction.NONE
    sdkInt >= 33 && (rationaleVisible || mode == NotificationPromptMode.AFTER_SAVE) ->
        NotificationPromptAction.REQUEST_PERMISSION
    sdkInt >= 33 -> NotificationPromptAction.OPEN_SYSTEM_SETTINGS
    mode == NotificationPromptMode.USER_INITIATED -> NotificationPromptAction.OPEN_SYSTEM_SETTINGS
    else -> NotificationPromptAction.NONE
}
