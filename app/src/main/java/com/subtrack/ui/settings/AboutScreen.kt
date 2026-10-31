package com.subtrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.subtrack.BuildConfig
import com.subtrack.SubTrackApp
import com.subtrack.domain.model.AppSettings
import com.subtrack.network.NetworkTransport
import com.subtrack.network.RequestLease
import com.subtrack.ui.network.UpdatePanel
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 关于与更新。
 *
 * 检查更新从「联网服务」那一串 AI/模板表单里搬出来了：它不是联网能力里的一个开关，
 * 而是"这个应用是不是最新"的问题。所有更新保护（HTTPS、清单签名、哈希、证书一致、
 * 系统安装确认）保持原样，没有任何一步被简化。
 */
@Composable
internal fun AboutScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { (context.applicationContext as SubTrackApp).container.settingsStore }
    val transport = remember { NetworkTransport(BuildConfig.DEBUG) }
    val updateLease = remember { RequestLease() }
    DisposableEffect(updateLease) { onDispose { updateLease.close() } }
    val n = settings.network

    SettingsPage(title = "关于与更新", onBack = onBack, listTag = "about-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "版本") {
                SettingsValueRow(title = "当前版本", value = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                SettingsDivider(withIcon = false)
                SettingsValueRow(
                    title = "更新源",
                    value = when {
                        n.updateManifestUrl.isNotBlank() -> "自签清单"
                        BuildConfig.UPDATE_GITHUB_REPO.isNotBlank() -> "GitHub · ${BuildConfig.UPDATE_GITHUB_REPO}"
                        else -> "未配置"
                    },
                )
                SettingsDivider(withIcon = false)
                SettingsValueRow(
                    title = "签名校验公钥",
                    value = if (BuildConfig.UPDATE_PUBLIC_KEY.isBlank()) "未固定（仅用证书一致性校验）" else "已固定",
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "检查更新",
                footer = "只有启用后才会在你点击时访问 GitHub 发布页；不会后台自动下载，也不会静默安装。",
            ) {
                SettingsToggleRow(
                    title = "允许检查新版本",
                    subtitle = "关闭后不会发起任何更新请求",
                    checked = n.updateEnabled,
                    tag = "update-toggle",
                    onToggle = { value ->
                        updateLease.invalidate()
                        scope.launch { store.updateNetwork { it.copy(updateEnabled = value) } }
                    },
                )
                SettingsDivider(withIcon = false)
                var urlDraft by remember(n.updateManifestUrl) { mutableStateOf(n.updateManifestUrl) }
                val dirty = urlDraft.trim() != n.updateManifestUrl
                SettingsTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    label = "自签清单地址（可选）",
                    placeholder = "留空使用 GitHub Releases",
                    helper = if (dirty) "有未保存的修改" else "填了就只用这份签名清单，不会回落到 GitHub",
                    tag = "update-endpoint",
                )
                SettingsButton(
                    text = "保存更新源",
                    onClick = {
                        val next = urlDraft.trim()
                        updateLease.invalidate()
                        scope.launch { store.updateNetwork { it.copy(updateManifestUrl = next) } }
                    },
                    style = ActionStyle.Secondary,
                    enabled = dirty,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.runtime.key(n.updateEnabled, n.updateManifestUrl, n.connectionRevision) {
                    UpdatePanel(n, transport, updateLease)
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsHelp("帮助：更新是怎么校验的") {
                SettingsParagraph("GitHub 通道：读取仓库的最新 Release，版本号取 tag、版本码取 APK 文件名，两者对不上就拒绝。")
                SettingsParagraph("自签清单通道（可选）：只接受 HTTPS 清单，且必须由内置公钥验签；填了清单地址就只用它。")
                SettingsParagraph("下载的 APK 会校验大小、版本码与签名证书，必须与当前安装是同一张证书才会交给系统安装器；清单带哈希时还会核对哈希。")
                SettingsParagraph("最后一步一定由系统安装界面确认，应用不会静默安装；签名不一致时不会删除或替换原应用。")
                SettingsParagraph("更新源为空时不会发起请求，也不会回落到任何第三方镜像。")
                Text(
                    "本地优先：默认不上传台账，断网仍可记账、备份和提醒。",
                    style = Type.caption,
                    color = InkSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}
