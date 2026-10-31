package com.subtrack.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 跳到系统的本应用通知设置。
 *
 * 失败时静默：部分壳机/受限模式下没有该 Activity，抛异常会直接崩在设置页上，
 * 而用户此刻要做的事情（打开通知）仍然可以在系统设置里手动完成。
 */
fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}
