package com.subtrack.ui.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.subtrack.sync.ConflictView
import com.subtrack.sync.SyncStatusSnapshot
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.settings.SettingsButton
import com.subtrack.ui.settings.SettingsDivider
import com.subtrack.ui.settings.SettingsTextAction
import com.subtrack.ui.settings.Tone
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type

/**
 * 同步状态卡（纯展示）。
 *
 * 只回答三件事：现在是连着的还是没连、有多少等着上传、有没有冲突。
 * 恢复包、密钥代次、cursor 这些放在同步管理页的诊断区，不往这条状态里拼。
 *
 * [onRotateKey] 为空表示调用方把"轮换密钥"放在恢复与密钥分区（危险操作区），
 * 不再在状态卡里重复一个按钮。
 */
@Composable
internal fun SyncSettingsPanel(
    status: SyncStatusSnapshot?,
    conflicts: List<ConflictView>,
    reminderRoleLabel: String,
    onSyncNow: () -> Unit,
    onRotateKey: (() -> Unit)?,
    onKeepLocal: (String) -> Unit,
    onKeepRemote: (String) -> Unit,
) {
    GlassCard(padding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (status == null) "多设备同步" else "多设备同步 · 已连接",
                style = Type.subhead,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
        }
        if (status == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "同步未启用",
                Modifier.testTag("sync-disabled"),
                style = Type.footnote,
                color = InkSecondary,
            )
            return@GlassCard
        }
        Spacer(Modifier.height(6.dp))
        // 只放用户能判断的东西：待上传、冲突、上次修订。设备 uuid / epoch / cursor 在诊断区。
        Text(
            "待上传：${status.pendingUploads}\n冲突：${status.conflicts}\n上次同步修订：${status.lastSyncedRevision}",
            Modifier.testTag("sync-status-text"),
            style = Type.footnote,
            color = InkSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onRotateKey != null) {
                SettingsTextAction("轮换密钥", onClick = onRotateKey, tag = "sync-rotate")
            }
            Spacer(Modifier.weight(1f))
            Column {
                SettingsButton(
                    text = "立即同步",
                    onClick = onSyncNow,
                    tag = "sync-now",
                    modifier = Modifier.width(160.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "提醒角色：$reminderRoleLabel",
            Modifier.testTag("sync-role"),
            style = Type.caption,
            color = Ink,
        )
        Spacer(Modifier.height(8.dp))
        SyncConflictPanel(conflicts, onKeepLocal = onKeepLocal, onKeepRemote = onKeepRemote)
    }
}

/**
 * 冲突列表：每条一个可读标签 + 两个明确选择（保留本机 / 采用远端），从不静默覆盖。
 */
@Composable
internal fun SyncConflictPanel(
    conflicts: List<ConflictView>,
    onKeepLocal: (String) -> Unit,
    onKeepRemote: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("sync-conflict-panel")) {
        Text("同步冲突（${conflicts.size}）", style = Type.footnote, color = Ink)
        if (conflicts.isEmpty()) {
            Text("无冲突", Modifier.padding(top = 4.dp).testTag("sync-conflict-empty"), style = Type.caption, color = InkSecondary)
            return@Column
        }
        conflicts.forEach { c ->
            Column(Modifier.fillMaxWidth()) {
                SettingsDivider(withIcon = false)
                Text(
                    c.label,
                    Modifier.padding(top = 8.dp).testTag("sync-conflict-${c.operationId}"),
                    style = Type.caption,
                    color = com.subtrack.ui.theme.Danger,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SettingsTextAction("保留本机", onClick = { onKeepLocal(c.operationId) }, tag = "sync-keep-local-${c.operationId}")
                    SettingsTextAction("采用远端", onClick = { onKeepRemote(c.operationId) }, tag = "sync-keep-remote-${c.operationId}")
                }
            }
        }
    }
}
