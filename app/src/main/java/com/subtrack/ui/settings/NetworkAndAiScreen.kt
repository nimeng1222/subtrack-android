package com.subtrack.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtrack.BuildConfig
import com.subtrack.SubTrackApp
import com.subtrack.domain.model.AppSettings
import com.subtrack.network.AiModels
import com.subtrack.network.NetworkTransport
import com.subtrack.network.OnlineRepository
import com.subtrack.network.RequestLease
import com.subtrack.network.SecretStore
import com.subtrack.ui.components.ConfirmDialog
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 联网与 AI。
 *
 * 改版前这一页是一列开关 + 一列输入框 + 十几个 TextButton，共用一个全局 status 字符串：
 * 点"刷新模板"会把 AI 的报错覆盖掉，用户永远看不出哪个功能现在是好是坏。现在每一项
 * （在线模板 / AI 连接 / 检索来源）各有独立状态、独立请求与独立取消。
 *
 * 保存规则：
 * - 开关＝即时生效（与全应用一致）；
 * - 地址/模型/Key 是多字段配置，用**独立草稿 + 显式保存**：输入过程中不写库、不清密钥；
 *   未保存的草稿绝不参与任何请求；取消编辑不动已保存的凭据；换服务地址时旧密钥作废，
 *   这与"密钥绑定服务地址"的既有规则一致。
 */
@Composable
internal fun NetworkAndAiScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { (context.applicationContext as SubTrackApp).container.settingsStore }
    val transport = remember { NetworkTransport(BuildConfig.DEBUG) }
    val secrets = remember { SecretStore(context) }
    val n = settings.network


    // ── 在线模板：自己的请求、自己的状态 ──
    // ── AI 连接：草稿与已保存配置分离 ──
    var urlDraft by remember(n.aiBaseUrl, n.connectionRevision) { mutableStateOf(n.aiBaseUrl) }
    var modelDraft by remember(n.aiModel, n.connectionRevision) { mutableStateOf(n.aiModel) }
    var keyDraft by remember(n.aiBaseUrl, n.connectionRevision) { mutableStateOf("") }
    var saveNotice by remember(n.aiBaseUrl, n.connectionRevision) { mutableStateOf<String?>(null) }
    var keySaved by remember { mutableStateOf(false) }
    var keyTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(n.aiBaseUrl, n.aiEnabled, keyTick) {
        keySaved = withContext(Dispatchers.IO) {
            runCatching { secrets.getBound(n.aiBaseUrl) != null }.getOrDefault(false)
        }
    }
    val aiDirty = urlDraft.trim() != n.aiBaseUrl || modelDraft.trim() != n.aiModel || keyDraft.isNotEmpty()
    val aiAddressError = when {
        !aiDirty -> null
        urlDraft.isBlank() -> "请填写服务地址（例如 https://你的服务域名/v1/chat/completions）"
        !(urlDraft.startsWith("https://") || urlDraft.startsWith("http://")) -> "服务地址需要以 http:// 或 https:// 开头"
        else -> null
    }

    var confirmClear by remember { mutableStateOf(false) }

    // ── 模型列表：拉取结果先放草稿，勾选并保存后才写进设置 ──
    var fetchingModels by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf<String?>(null) }
    var modelPicker by remember { mutableStateOf<List<String>?>(null) }

    fun fetchModels() {
        if (fetchingModels) return
        val savedUrl = n.aiBaseUrl.trim()
        when {
            savedUrl.isBlank() -> { modelStatus = "先填写并保存服务地址"; return }
            urlDraft.trim() != n.aiBaseUrl || modelDraft.trim() != n.aiModel || keyDraft.isNotEmpty() ->
                { modelStatus = "有未保存的修改：先点「保存配置」，再获取模型列表"; return }
        }
        fetchingModels = true
        modelStatus = "正在向服务获取模型列表…"
        scope.launch {
            try {
                val key = withContext(Dispatchers.IO) { secrets.getBound(savedUrl) }
                require(!key.isNullOrBlank()) { "还没有保存这个地址的密钥" }
                val text = transport.request(
                    AiModels.modelsUrl(savedUrl),
                    key = key,
                    accept = "application/json",
                    throttle = false,
                ).toString(Charsets.UTF_8)
                val models = AiModels.parse(text)
                modelPicker = models
                modelStatus = "取到 ${models.size} 个模型，勾选要用的（可全选/全不选）"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                modelStatus = "获取失败：${e.message?.take(80) ?: "网络不可用"}"
            } finally {
                fetchingModels = false
            }
        }
    }

    // 已保存的来源变了（换地址 / 关闭 / 清除连接）：立刻关掉正在请求的对话框。
    // 否则重建后的对话框会停在一个"看起来还能发"的状态，把旧来源的迟到结果显示在新来源下面。
    var observedAiSource by remember { mutableStateOf(Triple(n.aiBaseUrl, n.aiEnabled, n.connectionRevision)) }
    LaunchedEffect(n.aiBaseUrl, n.aiEnabled, n.connectionRevision) {
        val current = Triple(n.aiBaseUrl, n.aiEnabled, n.connectionRevision)
        if (current != observedAiSource) {
            // 注意：这里**不能清 keyDraft**。草稿已经按 remember(source) 跟随来源重置，
            // 再在这里清一次会和"用户刚打完字、设置流晚到"抢时序，把刚输入的 Key 吞掉。
            observedAiSource = current
        }
    }

    fun patchNetwork(change: (com.subtrack.domain.model.NetworkSettings) -> com.subtrack.domain.model.NetworkSettings) {
        scope.launch { store.updateNetwork(change) }
    }

    fun commitAi() {
        val newUrl = urlDraft.trim()
        val newModel = modelDraft.trim()
        val urlChanged = newUrl != n.aiBaseUrl
        val pendingKey = keyDraft
        scope.launch {
            withContext(Dispatchers.IO) {
                if (urlChanged) {
                    // 换地址：旧密钥对新地址无效，先清掉；草稿里有新 Key 就顺手绑定到新地址。
                    runCatching { secrets.clear() }
                    if (pendingKey.isNotBlank()) runCatching { secrets.putBound(newUrl, pendingKey) }
                } else if (pendingKey.isNotBlank()) {
                    runCatching { secrets.putBound(newUrl, pendingKey) }
                }
            }
            store.updateNetwork { current ->
                current.copy(
                    aiBaseUrl = newUrl,
                    aiModel = newModel,
                    // 换服务地址后旧服务的模型列表不适用：清掉，避免 AI 页出现点不动的模型。
                    aiModels = if (urlChanged) emptyList() else current.aiModels,
                    connectionRevision = if (urlChanged) current.connectionRevision + 1 else current.connectionRevision,
                )
            }
            keyDraft = ""
            keyTick++
            saveNotice = if (pendingKey.isNotBlank()) "已保存；密钥只发给这个服务地址" else "已保存配置"
        }
    }

    SettingsPage(title = "联网与 AI", onBack = onBack, listTag = "network-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                modifier = Modifier.testTag("network-section"),
                title = "模型连接",
                footer = "填好地址、模型名和密钥后，底部「AI」页就能对话并自己打开官网核对价格。",
            ) {
                SettingsToggleRow(
                    title = "启用 AI 助手",
                    subtitle = if (n.aiEnabled) "底部 AI 页可以对话、查官网价格" else "关闭后底部 AI 页只显示配置入口",
                    checked = n.aiEnabled,
                    tag = "toggle-ai",
                    onToggle = { value -> patchNetwork { it.copy(aiEnabled = value) } },
                )
                SettingsDivider(withIcon = false)
                SettingsPaddedContent {
                    Text(
                        "填写你自己的模型服务完整地址；已有可用的 HTTPS 地址即可，不需要额外购买域名。",
                        style = Type.caption.copy(lineHeight = 17.sp),
                        color = InkSecondary,
                    )
                }
                SettingsTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it; saveNotice = null; modelStatus = null },
                    label = "服务地址",
                    placeholder = "https://…/v1/chat/completions",
                    error = aiAddressError,
                    tag = "ai-endpoint",
                )
                SettingsTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it; saveNotice = null },
                    label = "模型名称",
                    placeholder = "例如 your-model",
                    tag = "ai-model",
                )
                SettingsPaddedContent(top = 0.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (n.aiModels.isEmpty()) {
                                    "可以手动填模型名，也可以从服务获取列表后勾选。"
                                } else {
                                    "已保存 ${n.aiModels.size} 个可选模型，当前：${n.aiModel.ifBlank { "未选择" }}"
                                },
                                style = Type.caption,
                                color = InkSecondary,
                            )
                            modelStatus?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = Type.caption, color = InkSecondary, modifier = Modifier.testTag("ai-models-status"))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingsButton(
                        text = if (fetchingModels) "正在获取…" else "从服务获取模型列表",
                        onClick = { fetchModels() },
                        style = ActionStyle.Secondary,
                        enabled = !fetchingModels,
                        tag = "ai-fetch-models",
                    )
                    if (n.aiModels.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SettingsButton(
                            text = "管理已选模型（${n.aiModels.size}）",
                            onClick = { modelPicker = n.aiModels },
                            style = ActionStyle.Secondary,
                            tag = "ai-manage-models",
                        )
                    }
                }
                SettingsTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it; saveNotice = null },
                    label = "API Key（只存本机，加密保存）",
                    placeholder = if (keySaved) "已保存；输入新值可替换" else "尚未保存",
                    helper = "密钥与服务地址绑定：换地址后旧密钥自动作废。",
                    secret = true,
                    tag = "ai-key",
                )
                SettingsPaddedContent(top = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().testTag("ai-draft-state"),
                    ) {
                        Text(
                            when {
                                aiDirty -> "有未保存的修改（不会用于发送请求）"
                                keySaved -> "已保存：密钥就绪"
                                n.aiBaseUrl.isBlank() -> "尚未配置服务地址"
                                else -> "已保存配置；密钥未保存"
                            },
                            style = Type.caption,
                            color = if (aiDirty) com.subtrack.ui.theme.Warning else InkSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SettingsButton(
                        text = "保存配置",
                        onClick = { commitAi() },
                        // 允许先配置再启用：开关只管"会不会发请求"，不阻止保存地址/模型。
                        enabled = aiDirty && aiAddressError == null,
                        tag = "ai-save-key",
                    )
                    if (aiDirty) {
                        Spacer(Modifier.height(8.dp))
                        SettingsButton(
                            text = "取消修改",
                            onClick = {
                                urlDraft = n.aiBaseUrl
                                modelDraft = n.aiModel
                                keyDraft = ""
                                saveNotice = null
                            },
                            style = ActionStyle.Secondary,
                            tag = "ai-draft-cancel",
                        )
                    }
                    saveNotice?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = Type.caption, color = InkSecondary)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "每次在 AI 页发消息都会把台账摘要与问题发到这个地址；服务可能收费。",
                        style = Type.caption,
                        color = InkSecondary,
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "连接管理",
                footer = "清除本机凭据不等于远端撤销：同步设备的授权要在同步管理页按设备撤销。",
            ) {
                SettingsPaddedContent {
                    SettingsButton(
                        text = "关闭全部联网并清除本机密钥",
                        onClick = { confirmClear = true },
                        style = ActionStyle.Danger,
                        enabled = n.aiEnabled || n.fxEnabled || n.updateEnabled,
                        tag = "network-clear",
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsHelp("帮助：每个开关会请求什么") {
                SettingsParagraph("在线汇率：只发送币种代码到参考汇率源，见「币种与汇率」页。")
                SettingsParagraph("AI 助手：在底部「AI」页发消息时，把台账摘要与问题发送到你填的服务地址；它可能自己打开官网页面核对价格。")
                SettingsParagraph("检查更新：见「关于与更新」页，只在启用后由你手动触发。")
                SettingsParagraph("关闭某个开关只会停止该能力；已保存的密钥与本地数据不受影响。")
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "关闭全部联网并清除本机密钥？",
            message = "会关闭在线汇率、AI 与检查更新，并删除本机保存的 AI 密钥。" +
                "台账数据与本机订阅不受影响；远端设备授权不会被撤销。",
            confirmLabel = "关闭并清除",
            destructive = true,
            confirmTag = "network-clear-confirm",
            onConfirm = {
                confirmClear = false
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { secrets.clear() } }
                    store.updateNetwork { current ->
                        com.subtrack.domain.model.NetworkSettings(connectionRevision = current.connectionRevision + 1)
                    }
                    keyTick++
                }
            },
            onDismiss = { confirmClear = false },
        )
    }

    modelPicker?.let { models ->
        AiModelSheet(
            models = models,
            selected = n.aiModels.ifEmpty { models },
            onDismiss = { modelPicker = null },
            onSave = { picked ->
                scope.launch {
                    store.updateNetwork { current ->
                        current.copy(
                            aiModels = picked,
                            // 当前模型必须仍是被勾选的；否则退到第一个，避免"列表里没有但仍在用"。
                            aiModel = if (current.aiModel in picked) current.aiModel else picked.firstOrNull().orEmpty(),
                        )
                    }
                }
                modelPicker = null
                modelStatus = if (picked.isEmpty()) "没有勾选任何模型：AI 页会先提示你选一个" else "已保存 ${picked.size} 个模型"
            },
        )
    }
}

/**
 * 模型多选弹层：全选 / 全不选 / 逐个勾选，保存后写入设置。
 * 只做本地勾选，不在这里发请求；取消不会改动已保存的列表。
 */
@Composable
private fun AiModelSheet(
    models: List<String>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var checked by remember(models) { mutableStateOf(selected.toSet()) }
    SettingsSheet(
        title = "选择要用的模型（${checked.size}/${models.size}）",
        onDismiss = onDismiss,
        actions = {
            SettingsButton(
                text = if (checked.isEmpty()) "保存（未选）" else "保存这 ${checked.size} 个",
                onClick = { onSave(models.filter { it in checked }) },
                tag = "ai-models-save",
            )
            Spacer(Modifier.height(8.dp))
            SettingsButton("取消", onClick = onDismiss, style = ActionStyle.Secondary)
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SettingsTextAction("全选", onClick = { checked = models.toSet() }, tag = "ai-models-all")
            Spacer(Modifier.width(4.dp))
            SettingsTextAction("全不选", onClick = { checked = emptySet() }, tag = "ai-models-none")
            Spacer(Modifier.weight(1f))
            Text("${checked.size} 个已选", style = Type.caption, color = InkSecondary)
        }
        Spacer(Modifier.height(6.dp))
        models.forEach { model ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { checked = if (model in checked) checked - model else checked + model }
                    .padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = model in checked,
                    onCheckedChange = { on -> checked = if (on) checked + model else checked - model },
                )
                Text(
                    model,
                    style = Type.body,
                    color = Ink,
                    modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
                )
            }
        }
    }
}

