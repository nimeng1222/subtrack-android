package com.subtrack

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.subtrack.data.settings.permitsContent
import com.subtrack.ui.lock.AppLock
import com.subtrack.ui.lock.LockScreen

/**
 * 应用锁门卫。
 *
 * 安全规则（v1.3.2 审查结论）：
 * - 锁屏上**没有**免认证的入口。"关闭应用锁"与"解锁"走同一条生物识别请求，
 *   通过认证后进的是已解锁的 App——要真正关掉锁，去设置页操作；
 * - 生物识别不可用（无硬件/未录入/暂不可用）或用户取消：保持锁定，只给提示；
 * - 设置尚未从 DataStore 加载完成时，按"可能有锁"处理：渲染空屏等待，
 *   绝不按默认的 appLockEnabled=false 提前渲染受保护内容。
 */
@Composable
fun LockGate(
    settingsState: com.subtrack.data.settings.SettingsState,
    authenticate: (((() -> Unit), (() -> Unit)) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // BiometricPrompt 硬要求 FragmentActivity；LocalActivity 在 activity-compose 1.10 起提供
    val activity = androidx.activity.compose.LocalActivity.current as? androidx.fragment.app.FragmentActivity
    // 键翻转（设置 Loading→Loaded、开关切换）时显式重置解锁态。不要用 remember(lockKey)
    // 做键控重置：那会替换 MutableState 实例，而下方 DisposableEffect 注册的观察者
    // 闭包仍捕获旧实例，ON_STOP 里的 unlocked=false 写不进当前状态——设置完成首次
    // 加载后，解锁过的会话回前台就不再重新锁（2026-10-28 设备实测缺陷）。
    var unlocked by remember { mutableStateOf(false) }
    val lockKey = settingsState is com.subtrack.data.settings.SettingsState.Loaded && settingsState.settings.appLockEnabled
    LaunchedEffect(lockKey) { unlocked = false }
    var generation by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var unavailable by remember { mutableStateOf<String?>(null) }

    val needLock = lockKey

    // 从后台回来要重新锁：ON_STOP 时清掉解锁态
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) { foreground = false; unlocked = false; generation++ }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val request: () -> Unit = {
        val requestedGeneration = generation
        val success = {
            if (generation == requestedGeneration && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                unlocked = true
                unavailable = null
            }
        }
        if (authenticate != null) {
            authenticate(success, { unavailable = null })
        } else if (activity == null) {
            unavailable = "当前系统无法使用生物识别"
        } else {
            AppLock.requestUnlock(
                activity = activity,
                onSuccess = success,
                // 用户取消 / 认证失败：保持锁定，安静等待下一次尝试
                onFailed = { unavailable = null },
                onUnavailable = { unavailable = "生物识别当前不可用，无法解锁" },
            )
        }
    }

    // 开启锁且未解锁时，自动弹一次
    LaunchedEffect(needLock, unlocked, foreground) {
        if (foreground && needLock && !unlocked) request()
    }

    when {
        // 设置没加载完之前不渲染任何受保护内容：默认拦截，宁可白屏一瞬
        settingsState is com.subtrack.data.settings.SettingsState.Loading -> androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .background(com.subtrack.ui.theme.PageBg),
        )

        needLock && !unlocked -> LockScreen(
            onUnlock = request,
            unavailableHint = unavailable,
        )

        else -> if (settingsState.permitsContent(unlocked)) content()
    }
}
