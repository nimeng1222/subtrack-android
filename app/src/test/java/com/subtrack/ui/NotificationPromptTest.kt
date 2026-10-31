package com.subtrack.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 保存订阅后绝不跳系统设置。
 *
 * 这是实机反馈的缺陷：存一条订阅 → 通知权限弹窗被拒（或早已拒绝）→ 应用直接把用户踢到
 * 系统通知设置页。规则收敛到 [notificationPromptAction]，这里把每种组合都钉住。
 */
class NotificationPromptTest {

    private val any = NotificationPromptMode.AFTER_SAVE
    private val user = NotificationPromptMode.USER_INITIATED

    @Test fun nothing_happens_when_notifications_already_work() {
        for (mode in NotificationPromptMode.entries) {
            for (sdk in listOf(26, 32, 33, 34, 35)) {
                for (rationale in listOf(true, false)) {
                    assertEquals(
                        "already allowed: mode=$mode sdk=$sdk",
                        NotificationPromptAction.NONE,
                        notificationPromptAction(mode, sdk, canPost = true, rationaleVisible = rationale),
                    )
                }
            }
        }
    }

    @Test fun after_save_never_opens_system_settings_on_any_android_version() {
        for (sdk in listOf(26, 29, 32, 33, 34, 35)) {
            for (rationale in listOf(true, false)) {
                val action = notificationPromptAction(any, sdk, canPost = false, rationaleVisible = rationale)
                assertEquals(
                    "保存订阅后只能请求权限或什么都不做（sdk=$sdk rationale=$rationale），实际 $action",
                    false,
                    action == NotificationPromptAction.OPEN_SYSTEM_SETTINGS,
                )
            }
        }
    }

    @Test fun android_13_and_above_requests_the_runtime_permission() {
        for (sdk in listOf(33, 34, 35)) {
            // 应用自己触发的路径永远先请系统弹窗（永久拒绝时系统会静默返回 denied，不会跳页）。
            assertEquals(
                NotificationPromptAction.REQUEST_PERMISSION,
                notificationPromptAction(NotificationPromptMode.AFTER_SAVE, sdk, canPost = false, rationaleVisible = false),
            )
            // 用户点过"去开启"、系统还会再问 → 仍然先弹窗。
            assertEquals(
                NotificationPromptAction.REQUEST_PERMISSION,
                notificationPromptAction(NotificationPromptMode.USER_INITIATED, sdk, canPost = false, rationaleVisible = true),
            )
        }
    }

    @Test fun user_initiated_prompt_opens_settings_only_when_the_system_will_not_ask_again() {
        // Android 13+：用户点过"去开启"、系统已经不再弹窗 → 打开系统设置。
        assertEquals(
            NotificationPromptAction.OPEN_SYSTEM_SETTINGS,
            notificationPromptAction(user, 34, canPost = false, rationaleVisible = false),
        )
        // Android 12 及以下没有运行时权限弹窗：用户主动点"去开启"就直达系统设置。
        assertEquals(
            NotificationPromptAction.OPEN_SYSTEM_SETTINGS,
            notificationPromptAction(user, 30, canPost = false, rationaleVisible = false),
        )
        // 但同版本下自动路径仍然什么都不做。
        assertEquals(
            NotificationPromptAction.NONE,
            notificationPromptAction(any, 30, canPost = false, rationaleVisible = false),
        )
    }
}
