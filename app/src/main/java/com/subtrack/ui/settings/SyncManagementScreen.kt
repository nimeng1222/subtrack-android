package com.subtrack.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtrack.R
import com.subtrack.SubTrackApp
import com.subtrack.sync.ConflictView
import com.subtrack.sync.SyncProductService
import com.subtrack.sync.SyncProductStatus
import com.subtrack.sync.SyncRemoteDevice
import com.subtrack.sync.SyncStatusSnapshot
import com.subtrack.ui.components.ConfirmDialog
import com.subtrack.ui.network.SyncSettingsPanel
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 同步管理。
 *
 * 改版前同步是"一串 TextButton 叠在联网页底部"：创建/加入/上传/配对/同步/撤销/轮换/停用
 * 全部同权重排在一列，常用操作和一次性危险操作混在一起，状态只有一个会被覆盖的字符串。
 *
 * 现在分三段：
 * 1. 顶部状态卡：连没连上、待上传多少、有多少冲突、立即同步；
 * 2. 未配置时的分步设置（地址 → 凭据 → 初始合并），每步只显示当前需要的字段；
 * 3. 已配置后的分区：设备 / 冲突 / 提醒设备 / 恢复与密钥 / 停用期修改 / 诊断 / 危险操作。
 *
 * 恢复包与凭据默认遮罩，要看或复制都要明确点一下；cursor、密钥代次这类内部字段只在诊断区。
 */
@Composable
internal fun SyncManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as SubTrackApp).container }
    val service: SyncProductService = remember { container.syncService }

    var status by remember { mutableStateOf<SyncProductStatus?>(null) }
    var hasVault by remember { mutableStateOf(false) }
    var conflicts by remember { mutableStateOf<List<ConflictView>>(emptyList()) }
    var devices by remember { mutableStateOf<List<SyncRemoteDevice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var lastSuccessAt by remember { mutableLongStateOf(0L) }

    suspend fun refreshState() {
        withTimeoutOrNull(3_000) { service.ensureInitialized() }
        val snapshot = runCatching { service.status() }.getOrNull()
        status = snapshot
        hasVault = service.activeVault != null
        val vault = service.activeVault
        conflicts = if (vault != null) {
            runCatching { service.conflictsRepository().conflicts(vault) }.getOrDefault(emptyList())
        } else emptyList()
        devices = if (snapshot?.active == true) {
            runCatching { service.devices() }.getOrDefault(emptyList())
        } else emptyList()
    }

    fun run(block: suspend () -> Pair<String, Tone>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                note = block()
            } catch (e: CancellationException) {
                note = "已取消" to Tone.Neutral
                throw e
            } catch (e: Exception) {
                note = "失败：${e.message?.take(120) ?: "无法完成，请稍后重试"}" to Tone.Danger
            } finally {
                busy = false
                runCatching { refreshState() }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshState()
        loaded = true
    }

    // ── 未配置时的分步状态 ──
    var step by rememberSaveable { mutableIntStateOf(1) }
    // 创建/加入账本后，服务端已经启用同步，但"要不要把本机既有数据发布上去"仍是一个必须由用户
    // 决定的问题。用独立状态把它保持在屏幕上，避免刚建完账本就跳进管理视图、第 3 步再也看不到。
    var pendingInitialMerge by rememberSaveable { mutableStateOf(false) }
    var endpointDraft by remember { mutableStateOf("") }
    var deviceNameDraft by remember { mutableStateOf("") }
    var joinMode by rememberSaveable { mutableIntStateOf(0) } // 0=创建 1=加入
    var createdRecovery by remember { mutableStateOf<String?>(null) }
    var createdAuth by remember { mutableStateOf<String?>(null) }
    var mergePreview by remember { mutableStateOf<String?>(null) }
    // 敏感草稿：令牌、配对凭据、恢复包一律不进 saved state。
    var setupToken by remember { mutableStateOf("") }
    var pairingCredential by remember { mutableStateOf("") }
    var recoveryMaterial by remember { mutableStateOf("") }
    var importBundle by remember { mutableStateOf("") }
    var pairingOut by remember { mutableStateOf<String?>(null) }
    var rotatedMaterial by remember { mutableStateOf<String?>(null) }
    var previewSummary by remember { mutableStateOf("尚未预览（先预览，再确认发布）") }
    var batchToken by remember { mutableStateOf<String?>(null) }
    var confirmDisable by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<SyncRemoteDevice?>(null) }

    LaunchedEffect(loaded) {
        if (loaded && endpointDraft.isBlank()) endpointDraft = service.endpoint.orEmpty()
    }

    val active = status?.active == true
    val snapshot = status?.let {
        SyncStatusSnapshot(
            vaultId = it.vaultId.orEmpty(),
            deviceId = it.deviceId.orEmpty(),
            keyEpoch = it.keyEpoch ?: 0,
            cursor = it.lastCursor ?: 0L,
            pendingUploads = it.pendingUploads,
            conflicts = it.unresolvedConflicts,
        )
    }

    SettingsPage(title = "同步管理", onBack = onBack, listTag = "sync-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().testTag("sync-state-card")) {
            // ── 顶部状态 ──
            if (active) {
                SyncSettingsPanel(
                    status = snapshot,
                    conflicts = conflicts,
                    reminderRoleLabel = runCatching { service.reminderRole() }.getOrDefault("本机提醒"),
                    onSyncNow = {
                        run {
                            val result = service.syncNow()
                            val next = service.status()
                            lastSuccessAt = System.currentTimeMillis()
                            "同步完成：上传 ${result.uploaded}，应用 ${result.appliedOps}；待上传 ${next.pendingUploads}，冲突 ${next.unresolvedConflicts}" to Tone.Success
                        }
                    },
                    onRotateKey = null,
                    onKeepLocal = { opId ->
                        run {
                            val vault = service.activeVault ?: error("同步未启用")
                            service.conflictsRepository().resolveKeepLocal(vault, opId)
                            "已保留本机版本并发布解决操作" to Tone.Success
                        }
                    },
                    onKeepRemote = { opId ->
                        run {
                            val vault = service.activeVault ?: error("同步未启用")
                            service.conflictsRepository().resolveKeepRemote(vault, opId)
                            "已采用远端版本并发布解决操作" to Tone.Success
                        }
                    },
                )
            } else {
                SettingsStatusCard(
                    tone = if (hasVault) Tone.Warning else Tone.Muted,
                    title = when {
                        !loaded -> "正在读取同步状态…"
                        hasVault -> "同步已停用 · 数据仍在本机"
                        else -> "未启用同步 · 默认只在本机"
                    },
                    body = when {
                        !loaded -> "请稍候。"
                        hasVault -> "新编辑不会再上传，本机数据完整保留。可以恢复同步，或重新加入另一个账本。"
                        else -> "创建或加入账本后才会在同步服务上保存一份加密副本；在此之前，本应用不会向任何服务器上传台账。"
                    },
                    action = {
                        if (hasVault) SettingsTextAction("恢复同步", onClick = {
                            run {
                                service.reenable()
                                if (service.restoreBinding()) "已恢复同步（保留原 cursor/epoch 与本地数据）" to Tone.Success
                                else "恢复失败：${service.initError ?: "缺少凭据或密钥"}" to Tone.Danger
                            }
                        }, tag = "sync-reenable")
                    },
                )
                Spacer(Modifier.height(6.dp))
            }

            Text(
                when {
                    lastSuccessAt > 0L -> "本次运行最近一次成功：${clockLabel(lastSuccessAt)}"
                    active -> "尚未在本次运行中同步"
                    else -> ""
                },
                style = Type.caption,
                color = InkSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).testTag("sync-status"),
            )
            }

            note?.let { (message, tone) ->
                Spacer(Modifier.height(4.dp))
                SettingsStatusCard(
                    tone = tone,
                    title = when (tone) {
                        Tone.Danger -> "操作未完成"
                        Tone.Success -> "操作完成"
                        else -> "状态"
                    },
                    body = message,
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(10.dp))

            // ── 未配置或"刚建好账本还没决定初始合并"：分步设置 ──
            if ((!active && !hasVault) || pendingInitialMerge) {
                StepHeader(step = step, total = 3)
                when (step) {
                    1 -> SettingsGroup(
                        title = "第 1 步 · 同步服务地址",
                        footer = "填你自建或已有服务地址；必须能提供 HTTPS。不需要额外购买域名。",
                    ) {
                        SettingsTextField(
                            value = endpointDraft,
                            onValueChange = { endpointDraft = it },
                            label = "服务地址",
                            placeholder = "https://sync.example.com",
                            keyboardType = KeyboardType.Uri,
                            tag = "sync-endpoint",
                        )
                        SettingsPaddedContent {
                            SettingsButton(
                                text = "保存并继续",
                                onClick = {
                                    run {
                                        val bound = service.switchEndpoint(endpointDraft.trim())
                                        step = 2
                                        if (bound) "服务地址已保存并重新绑定当前账本：${endpointDraft.trim()}" to Tone.Success
                                        else "服务地址已保存：${endpointDraft.trim()}；新服务没有该账本凭据，请继续创建或加入" to Tone.Neutral
                                    }
                                },
                                enabled = endpointDraft.isNotBlank() && !busy,
                                tag = "sync-save-endpoint",
                            )
                        }
                    }

                    2 -> SettingsGroup(
                        title = "第 2 步 · 创建或加入账本",
                        footer = "创建会生成解密恢复包；加入需要上一台设备给出的配对凭据与恢复材料。",
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            SettingsSegmented(
                                options = listOf("创建新账本", "加入已有账本"),
                                selected = joinMode,
                                onSelect = { joinMode = it },
                            )
                        }
                        SettingsDivider(withIcon = false)
                        SettingsTextField(
                            value = deviceNameDraft,
                            onValueChange = { deviceNameDraft = it },
                            label = "本机设备名",
                            placeholder = "例如 手机",
                            tag = "sync-device-name",
                        )
                        if (joinMode == 0) {
                            SettingsTextField(
                                value = setupToken,
                                onValueChange = { setupToken = it },
                                label = "安装令牌（服务端安装时生成）",
                                secret = true,
                                tag = "sync-setup-token",
                            )
                            SettingsPaddedContent {
                                SettingsButton(
                                    text = "创建账本",
                                    onClick = {
                                        run {
                                            val created = service.createVault(setupToken, deviceNameDraft.ifBlank { "device" })
                                            createdRecovery = created.dataKeyRecovery
                                            createdAuth = created.authRecovery
                                            val preview = service.initialMergePreview()
                                            mergePreview = "本机已有：订阅 ${preview["subscriptions"]}，流水 ${preview["payments"]}，分类 ${preview["categories"]}"
                                            step = 3
                                            pendingInitialMerge = true
                                            "账本已创建；下一步确认是否上传本机既有数据" to Tone.Success
                                        }
                                    },
                                    enabled = setupToken.isNotBlank() && !busy,
                                    tag = "sync-create",
                                )
                            }
                        } else {
                            SettingsTextField(
                                value = pairingCredential,
                                onValueChange = { pairingCredential = it },
                                label = "一次性配对凭据",
                                secret = true,
                                tag = "sync-pairing",
                            )
                            SettingsTextField(
                                value = recoveryMaterial,
                                onValueChange = { recoveryMaterial = it },
                                label = "解密恢复包（与账本+代次绑定）",
                                secret = true,
                                tag = "sync-recovery",
                            )
                            SettingsPaddedContent {
                                SettingsButton(
                                    text = "加入账本",
                                    onClick = {
                                        run {
                                            service.joinVault(pairingCredential, recoveryMaterial, deviceNameDraft.ifBlank { "device" })
                                            val preview = service.initialMergePreview()
                                            mergePreview = "本机已有：订阅 ${preview["subscriptions"]}，流水 ${preview["payments"]}，分类 ${preview["categories"]}"
                                            step = 3
                                            pendingInitialMerge = true
                                            "已加入账本；下一步确认是否上传本机既有数据" to Tone.Success
                                        }
                                    },
                                    enabled = pairingCredential.isNotBlank() && recoveryMaterial.isNotBlank() && !busy,
                                    tag = "sync-join",
                                )
                            }
                        }
                        SettingsPaddedContent(top = 0.dp) {
                            SettingsTextAction("返回上一步", onClick = { step = 1 }, enabled = !busy)
                        }
                    }

                    else -> SettingsGroup(
                        title = "第 3 步 · 初始合并",
                        footer = "只有确认后才会把本机既有数据发布到账本；此前服务器上没有你的台账。",
                    ) {
                        SettingsPaddedContent(top = 12.dp) {
                            Text(mergePreview ?: "尚未预览", style = Type.footnote, color = InkSecondary)
                            Spacer(Modifier.height(10.dp))
                            SettingsButton(
                                text = "上传本地既有数据",
                                onClick = {
                                    run {
                                        val published = service.publishInitialSnapshot()
                                        step = 3
                                        pendingInitialMerge = false
                                        "已上传：订阅 ${published["subscriptions"]}，流水 ${published["payments"]}，分类 ${published["categories"]}" to Tone.Success
                                    }
                                },
                                enabled = !busy,
                                tag = "sync-publish-local",
                            )
                            Spacer(Modifier.height(8.dp))
                            SettingsTextAction(
                                "稍后再说（不上传）",
                                onClick = { pendingInitialMerge = false; step = 1 },
                                enabled = !busy,
                            )
                        }
                    }
                }

                if (createdRecovery != null || createdAuth != null) {
                    SettingsGroup(
                        title = "请立刻带外保存恢复材料",
                        footer = "恢复包只在这里显示一次；认证恢复凭据只用于账号恢复，不能解密数据。",
                    ) {
                        createdRecovery?.let {
                            MaskedSecret(label = "解密恢复包（vault+epoch）", value = it, tag = "sync-created-recovery")
                        }
                        if (createdRecovery != null && createdAuth != null) SettingsDivider(withIcon = false)
                        createdAuth?.let {
                            MaskedSecret(label = "认证恢复凭据", value = it, tag = "sync-created-auth")
                        }
                    }
                }

                if (service.endpoint != null) {
                    SettingsGroup(title = "服务连接") {
                        SettingsValueRow(title = "当前服务地址", value = service.endpoint.orEmpty(), tag = "sync-endpoint-current")
                    }
                }
            }

            // ── 已配置：分区管理（初始合并决定完之前先收起，避免和分步表单抢注意力） ──
            if (hasVault && !pendingInitialMerge) {
                if (active) {
                    SettingsGroup(
                        title = "设备",
                        footer = "撤销后该设备不能再上传或拉取；本机不会被远程清除。",
                    ) {
                        SettingsPaddedContent(top = 8.dp, bottom = 4.dp) {
                            SettingsButton(
                                text = if (busy) "处理中…" else "刷新状态 / 设备 / 冲突",
                                onClick = { run { refreshState(); "已刷新：设备 ${devices.size}，冲突 ${conflicts.size}" to Tone.Neutral } },
                                style = ActionStyle.Secondary,
                                enabled = !busy,
                                tag = "sync-status-refresh",
                            )
                        }
                        if (devices.isEmpty()) {
                            SettingsValueRow(title = "设备", value = "尚未获取", tag = "sync-devices-empty")
                        }
                        devices.forEach { device ->
                            SettingsDivider(withIcon = false)
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${device.name}${if (device.revoked) "（已撤销）" else ""}",
                                    style = Type.callout,
                                    color = if (device.revoked) InkSecondary else Ink,
                                    modifier = Modifier.weight(1f),
                                )
                                SettingsTextAction(
                                    text = if (device.revoked) "已撤销" else "撤销",
                                    enabled = !device.revoked && !busy,
                                    tone = Tone.Danger,
                                    tag = "sync-revoke",
                                    onClick = { revokeTarget = device },
                                )
                            }
                        }
                        SettingsPaddedContent(top = 10.dp) {
                            SettingsButton(
                                text = "生成配对凭据（给新设备）",
                                onClick = {
                                    run { pairingOut = service.pairingCredential(); "已生成一次性配对凭据，显示一次" to Tone.Success }
                                },
                                style = ActionStyle.Secondary,
                                enabled = !busy,
                                tag = "sync-pairing-code",
                            )
                        }
                        pairingOut?.let { MaskedSecret(label = "一次性配对凭据", value = it, tag = "sync-pairing-out") }
                    }

                    SettingsGroup(
                        title = "提醒设备",
                        footer = "指定一台设备发提醒后，本机不再发（避免同一条提醒响两次）。",
                    ) {
                        SettingsValueRow(
                            title = "本机角色",
                            value = runCatching { service.reminderRole() }.getOrDefault("未知"),
                            tag = "sync-role-label",
                        )
                        SettingsDivider(withIcon = false)
                        SettingsPaddedContent(top = 8.dp, bottom = 4.dp) {
                            SettingsButton(
                                text = "刷新可选设备",
                                onClick = { run { devices = service.devices(); "可选设备：${devices.joinToString { it.name }}" to Tone.Neutral } },
                                style = ActionStyle.Secondary,
                                enabled = !busy,
                                tag = "sync-role-devices",
                            )
                        }
                        devices.filter { !it.revoked }.forEach { device ->
                            SettingsDivider(withIcon = false)
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(device.name, style = Type.callout, color = Ink, modifier = Modifier.weight(1f))
                                SettingsTextAction(
                                    text = "指定提醒",
                                    enabled = !busy,
                                    tag = "sync-role-device",
                                    onClick = {
                                        run {
                                            service.setDesignatedReminderDevice(device.id)
                                            "提醒设备已指定为 ${device.name}：本机角色=${service.reminderRole()}" to Tone.Success
                                        }
                                    },
                                )
                            }
                        }
                        SettingsPaddedContent(top = 10.dp) {
                            SettingsButton(
                                text = "取消指定（本机提醒）",
                                onClick = {
                                    run { service.setDesignatedReminderDevice(null); "提醒角色：本机提醒（已重排提醒）" to Tone.Success }
                                },
                                style = ActionStyle.Secondary,
                                enabled = !busy,
                                tag = "sync-role-clear",
                            )
                        }
                    }

                    SettingsGroup(
                        title = "恢复与密钥",
                        footer = "换设备、丢密钥时才用这里。恢复包与凭据都只在显示时可见。",
                    ) {
                        SettingsTextField(
                            value = importBundle,
                            onValueChange = { importBundle = it },
                            label = "导入恢复包（已有会话时）",
                            secret = true,
                            tag = "sync-import-bundle",
                        )
                        SettingsPaddedContent(top = 2.dp, bottom = 4.dp) {
                            SettingsButton(
                                text = "导入恢复包",
                                onClick = { run { val epoch = service.importRecoveryBundle(importBundle); "已导入代次 $epoch 恢复包；旧代次仍可解密" to Tone.Success } },
                                style = ActionStyle.Secondary,
                                enabled = importBundle.isNotBlank() && !busy,
                                tag = "sync-import",
                            )
                        }
                        SettingsDivider(withIcon = false)
                        SettingsPaddedContent(top = 8.dp, bottom = 4.dp) {
                            SettingsButton(
                                text = "轮换密钥（显示新恢复材料）",
                                onClick = { confirmRotate = true },
                                style = ActionStyle.Secondary,
                                enabled = !busy,
                                tag = "sync-rotate",
                            )
                        }
                        rotatedMaterial?.let { MaskedSecret(label = "新恢复材料（带外交给其他设备）", value = it, tag = "sync-rotated-material") }
                    }
                }

                SettingsGroup(
                    title = "停用期修改",
                    footer = "预览与确认绑定同一批次：确认发布的正是你刚看到的那一批。",
                ) {
                    SettingsPaddedContent(top = 12.dp) {
                        SettingsButton(
                            text = "预览停用期修改",
                            onClick = {
                                run {
                                    val preview = service.previewDisabledBatch()
                                    batchToken = preview.token
                                    previewSummary = if (preview.counts.isEmpty()) {
                                        "没有待确认的停用期修改" + if (preview.queued > 0) "（已排队待上传 ${preview.queued} 条）" else ""
                                    } else {
                                        "待确认：订阅 ${preview.counts["subscription"] ?: 0} 条；付款 ${preview.counts["payment"] ?: 0} 条；" +
                                            "分类 ${preview.counts["category"] ?: 0} 条（已排队待上传 ${preview.queued} 条）"
                                    }
                                    "预览完成；确认发布的正是本批次内容" to Tone.Neutral
                                }
                            },
                            style = ActionStyle.Secondary,
                            enabled = !busy,
                            tag = "sync-disabled-preview",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(previewSummary, style = Type.footnote, color = InkSecondary, modifier = Modifier.testTag("sync-preview-summary"))
                        Spacer(Modifier.height(10.dp))
                        SettingsButton(
                            text = "确认并发布本批次",
                            onClick = {
                                run {
                                    val published = runCatching { service.publishDisabledEdits(batchToken) }
                                    if (published.isFailure) {
                                        val error = published.exceptionOrNull()?.message?.take(120)
                                        previewSummary = "发布失败：$error；待确认内容已保留，可重试或重新预览"
                                        "发布失败：$error" to Tone.Danger
                                    } else {
                                        val counts = published.getOrThrow()
                                        val summary = "订阅 ${counts["subscription"] ?: 0} / 付款 ${counts["payment"] ?: 0} / 分类 ${counts["category"] ?: 0}"
                                        batchToken = null
                                        previewSummary = "已排队待上传：$summary（不是已上传；恢复同步后点“立即同步”）"
                                        "已排队待上传：$summary" to Tone.Success
                                    }
                                }
                            },
                            // 必须先预览：没有批次令牌就没有可确认的内容，避免误触直接发布。
                            enabled = !busy && batchToken != null,
                            style = ActionStyle.Secondary,
                            tag = "sync-disabled-publish",
                        )
                    }
                }

                SettingsHelp("诊断详情（一般不需要看）") {
                    SettingsParagraph("账本：${service.activeVault?.take(8) ?: "无"}… · 密钥代次：${status?.keyEpoch ?: "无"}")
                    SettingsParagraph("游标 cursor：${status?.lastCursor ?: 0L}（服务端已应用到的操作序号，用于增量拉取）")
                    SettingsParagraph("服务地址：${service.endpoint ?: "未设置"}")
                    SettingsParagraph("认证恢复凭据与解密恢复包是两件东西：前者只能恢复账号登录，后者才能解密数据。两者都不要拼进截图或状态文本。")
                    if (service.initError != null) {
                        SettingsParagraph("最近一次初始化错误：${service.initError?.take(160)}")
                    }
                }

                SettingsGroup(title = "停用", footer = "停用只停止上传；本机数据与账本凭据都保留，可随时恢复。") {
                    SettingsPaddedContent(top = 10.dp) {
                        SettingsButton(
                            text = "停用同步",
                            onClick = { confirmDisable = true },
                            style = ActionStyle.Danger,
                            enabled = active && !busy,
                            tag = "sync-disable",
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDisable) {
        ConfirmDialog(
            title = "停用同步？",
            message = "停用后新编辑不再上传，本机数据完整保留。远端账本与设备授权不会因此撤销；需要时可在这里恢复同步。",
            confirmLabel = "停用",
            destructive = true,
            confirmTag = "sync-disable-confirm",
            onConfirm = {
                confirmDisable = false
                run { service.disable(); "已停用同步；本机数据保留，可随时恢复" to Tone.Success }
            },
            onDismiss = { confirmDisable = false },
        )
    }
    if (confirmRotate) {
        ConfirmDialog(
            title = "轮换密钥？",
            message = "会生成新的密钥代次，并显示一份新的恢复材料。其他设备需要这份新材料才能继续解密；旧代次仍可解密已收到的操作。",
            confirmLabel = "轮换并显示",
            confirmTag = "sync-rotate-confirm",
            onConfirm = {
                confirmRotate = false
                run {
                    val (epoch, material) = service.rotateKey()
                    rotatedMaterial = material
                    "已轮换到密钥代次 $epoch；请带外保存新恢复材料" to Tone.Success
                }
            },
            onDismiss = { confirmRotate = false },
        )
    }
    revokeTarget?.let { target ->
        ConfirmDialog(
            title = "撤销 ${target.name}？",
            message = "撤销后这台设备不能再上传或拉取；它本机已有的数据不会被远程删除。其他设备与账本不受影响。",
            confirmLabel = "撤销设备",
            destructive = true,
            confirmTag = "sync-revoke-confirm",
            onConfirm = {
                revokeTarget = null
                run { service.revoke(target.id); devices = runCatching { service.devices() }.getOrDefault(devices); "已撤销 ${target.name}" to Tone.Success }
            },
            onDismiss = { revokeTarget = null },
        )
    }
}

@Composable
private fun StepHeader(step: Int, total: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            val isCurrent = index + 1 == step
            val isDone = index + 1 < step
            androidx.compose.foundation.layout.Box(
                Modifier
                    .width(if (isCurrent) 22.dp else 8.dp)
                    .height(6.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .background(
                        when {
                            isCurrent -> Accent
                            isDone -> Accent.copy(alpha = 0.35f)
                            else -> InkSecondary.copy(alpha = 0.28f)
                        },
                    ),
            )
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("第 $step / $total 步", style = Type.caption, color = InkSecondary, modifier = Modifier.testTag("sync-step"))
    }
}

/** 遮罩展示的敏感材料：默认不可读，显示/复制都要明确点一下。 */
@Composable
private fun MaskedSecret(label: String, value: String, tag: String) {
    var shown by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.caption, color = InkSecondary, modifier = Modifier.weight(1f))
            SettingsTextAction(text = if (shown) "隐藏" else "显示", onClick = { shown = !shown })
            SettingsTextAction(
                text = "复制",
                onClick = {
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(label, value))
                    }
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (shown) value else "••••••••••••••••••••",
            style = Type.footnote.copy(fontFeatureSettings = "tnum"),
            color = Ink,
            modifier = Modifier.testTag(tag),
        )
    }
}

private fun clockLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
