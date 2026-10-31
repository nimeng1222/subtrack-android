package com.subtrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.subtrack.R
import com.subtrack.SubTrackApp
import com.subtrack.domain.model.AppSettings
import com.subtrack.network.SecretStore
import com.subtrack.ui.lock.AppLock
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 安全与隐私。
 *
 * 应用锁保持真实生物识别与前后台重锁语义；密钥/连接的**权威管理位置**在联网页，
 * 这里只显示状态并跳转过去，避免出现两套开关与两个"清除"按钮。
 */
@Composable
internal fun SecuritySettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onOpenNetwork: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val availability = remember { AppLock.availability(context) }
    val ready = availability == AppLock.Availability.READY
    val lockSubtitle = when (availability) {
        AppLock.Availability.READY -> if (settings.appLockEnabled) "回到应用时需要指纹或面容验证" else "开启后回到应用需要验证"
        AppLock.Availability.NOT_ENROLLED -> "系统里还没有录入指纹或面容"
        AppLock.Availability.NO_HARDWARE -> "这台设备没有生物识别硬件"
        AppLock.Availability.HW_UNAVAILABLE -> "生物识别硬件暂时不可用"
        AppLock.Availability.UNAVAILABLE -> "当前系统无法使用生物识别"
    }

    // 密钥状态要在后台读（解密 keystore 保护的密文），不能卡住首帧。
    var keySaved by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(settings.network.aiBaseUrl, settings.network.aiEnabled) {
        keySaved = withContext(Dispatchers.IO) {
            runCatching { SecretStore(context).getBound(settings.network.aiBaseUrl) != null }.getOrDefault(false)
        }
    }
    var syncBound by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        syncBound = runCatching {
            (context.applicationContext as SubTrackApp).container.syncService.activeVault != null
        }.getOrDefault(false)
    }

    SettingsPage(title = "安全与隐私", onBack = onBack, listTag = "security-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "应用锁",
                footer = if (ready) "只做本机生物识别，不使用设备 PIN 作为回退；回到前台时重新验证。" else
                    "这台设备当前无法使用生物识别，应用锁无法开启。",
            ) {
                SettingsToggleRow(
                    title = "应用锁",
                    subtitle = lockSubtitle,
                    checked = settings.appLockEnabled,
                    // 硬件不可用时不允许开启，但允许关掉已开启的锁，避免用户被锁在自己外面。
                    enabled = ready || settings.appLockEnabled,
                    tag = "toggle-app-lock",
                    onToggle = { on -> onUpdate(settings.copy(appLockEnabled = on)) },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "密钥与连接",
                footer = "密钥只在本机加密保存，并绑定到对应服务地址；换地址后旧密钥不会自动沿用。",
            ) {
                SettingsValueRow(
                    title = "AI 服务密钥",
                    value = when {
                        !settings.network.aiEnabled -> "未启用"
                        keySaved == null -> "读取中…"
                        keySaved == true -> "已保存"
                        else -> "未保存"
                    },
                    tag = "security-ai-key-state",
                )
                SettingsDivider(withIcon = false)
                SettingsValueRow(
                    title = "同步凭据",
                    value = if (syncBound) "已保存" else "未保存",
                    tag = "security-sync-credential-state",
                )
                SettingsDivider(withIcon = false)
                SettingsNavRow(
                    icon = R.drawable.ic_wifi,
                    title = "管理联网、密钥与连接",
                    status = "在联网与 AI 页",
                    tag = "security-open-network",
                    onClick = onOpenNetwork,
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "数据去向") {
                SettingsValueRow(title = "默认", value = "只存本机")
                SettingsDivider(withIcon = false)
                SettingsValueRow(title = "会上传的内容", value = "仅你确认的批次")
                SettingsDivider(withIcon = false)
                SettingsValueRow(title = "不会上传", value = "未启用的服务与密钥")
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsHelp("帮助：本地优先与发送范围") {
                SettingsParagraph("所有记账、统计、提醒与备份都在本机完成，断网可用。")
                SettingsParagraph("联网能力逐项开关：在线模板、在线汇率、AI 分析、价格检索、检查更新各自独立，关掉一项不影响其他项。")
                SettingsParagraph("AI 分析只发送你在确认框里核对过的字段；粘贴的原文与台账不会自动上传。")
                SettingsParagraph("清除本机密钥不会撤销服务端的会话或设备授权——远端撤销要在同步管理页对具体设备执行。")
            }
        }
    }
}
