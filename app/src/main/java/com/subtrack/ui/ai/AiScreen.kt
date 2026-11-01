package com.subtrack.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.SubTrackApp
import com.subtrack.ai.AiConversation
import com.subtrack.ai.ChatTurn
import com.subtrack.domain.model.AppSettings
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.home.FloatingTabBar
import com.subtrack.ui.settings.ActionStyle
import com.subtrack.ui.settings.SettingsButton
import com.subtrack.ui.settings.SettingsTextAction
import com.subtrack.ui.settings.Tone
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Danger
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.InkTertiary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * AI 助手页（底部第四个入口）。
 *
 * 与设置里的"模型配置"分开：设置只负责地址/模型/Key，这里负责真正的对话。
 * 它能做三件事：读本机台账回答问题、先搜索再打开官网/网页核对价格、把两边放在一起比较。
 *
 * 对话记录由 [com.subtrack.ai.AiChatStore] 持久化（应用私有目录），切换底部 Tab 或重启后恢复；
 * 顶部「对话」可查看历史、切换、删除单段对话，「新建」开始一段新对话。
 *
 * 每次发送都会把「台账摘要 + 你的问题」发给你配置的模型服务；抓取的网页正文只用于本次回答，
 * 不写入台账。生成任务跑在 [com.subtrack.ai.AiChatRunner]（AppContainer 的应用级作用域里）：
 * 切到「订阅/统计」再回来不会打断，进度和结果都会重新挂接；用户点「停止」仍然立即生效；
 * 迟到的响应不会再回到对话里。进程被系统杀掉后任务仍会中断（没有前台服务，这是明确取舍）。
 */
@Composable
fun AiScreen(
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onSelectTab: (Int) -> Unit,
    /** 切换当前使用的模型（设置里勾选过的那些）。 */
    onSelectModel: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as SubTrackApp).container }
    val chatStore = container.aiChats
    // 生成任务由应用级 Runner 持有：本页只是"挂接"它的状态，离开组合不会取消任务。
    val runner = container.aiRunner
    val runState by runner.state.collectAsState()
    val chatState by chatStore.state.collectAsState()
    val conversations = chatState.conversations
    val currentConversation = conversations.firstOrNull { it.id == chatState.currentId }
    val turns = currentConversation?.turns.orEmpty()

    LaunchedEffect(Unit) { chatStore.ensureLoaded() }

    var input by remember { mutableStateOf("") }
    var showChats by remember { mutableStateOf(false) }
    // 进度与错误都来自 Runner 的 StateFlow：切走再回来能看到任务仍在跑，或看到它已经完成的结果。
    val running = runState.running
    val progress = runState.progress
    val error = runState.error

    // 已保存的来源变了（换地址 / 关开关 / 清除连接）：取消在途请求，迟到的回答不能再回到对话里。
    LaunchedEffect(settings.network.aiBaseUrl, settings.network.connectionRevision, settings.network.aiEnabled) {
        runner.onSourceChanged()
    }

    val configured = settings.network.aiEnabled && settings.network.aiBaseUrl.isNotBlank()
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, progress) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size)
    }

    fun startNewConversation() {
        if (running) return
        runner.stop()
        runner.clearError()
        input = ""
        showChats = false
        scope.launch { chatStore.startDraft() }
    }

    fun send() {
        val question = input.trim()
        if (question.isEmpty() || running) return
        if (!configured) { onOpenSettings(); return }
        // 真正的请求流程在 AiChatRunner 里：它跑在 AppContainer 的应用级作用域上，
        // 切换底部 Tab / 页面重建都不会取消生成；这里只负责把问题交出去。
        input = ""
        runner.clearError()
        runner.send(question)
    }

    if (showChats) {
        ConversationManagerDialog(
            conversations = conversations,
            currentId = chatState.currentId,
            onClose = { showChats = false },
            onSelect = { id ->
                showChats = false
                scope.launch { chatStore.select(id) }
            },
            onNew = { startNewConversation() },
            onDelete = { conversation ->
                // 删除当前对话时，先取消在途请求，避免回答写回一段已经删掉的记录。
                runner.stopIf(conversation.id)
                scope.launch { chatStore.delete(conversation.id) }
            },
        )
    }

    AmbientBackground {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 助手", style = Type.title2, color = Ink, modifier = Modifier.weight(1f))
                    SettingsTextAction(
                        "对话",
                        onClick = { showChats = true },
                        enabled = !running,
                        tag = "ai-chat-open",
                    )
                    SettingsTextAction(
                        "新建",
                        onClick = { startNewConversation() },
                        enabled = !running,
                        tag = "ai-chat-new-top",
                    )
                }
                if (configured) {
                    // 设置里勾了多个模型时，这里可以随时切换；只有一个就不占地方。
                    if (settings.network.aiModels.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            settings.network.aiModels.forEach { model ->
                                com.subtrack.ui.settings.ChoiceChip(
                                    label = model.take(18),
                                    selected = model == settings.network.aiModel,
                                    tag = "ai-model-$model",
                                ) { onSelectModel(model) }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }

            if (!configured) {
                Box(Modifier.weight(1f).padding(horizontal = 20.dp), contentAlignment = Alignment.TopCenter) {
                    GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp), padding = PaddingValues(18.dp)) {
                        Text("还没有配置模型", style = Type.headline, color = Ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "AI 助手要用你自己的模型服务。填好服务地址、模型名和密钥后，这里就能对话、" +
                                "让它去官网核对价格、以及回答你台账里的问题。",
                            style = Type.footnote.copy(lineHeight = 20.sp),
                            color = InkSecondary,
                        )
                        Spacer(Modifier.height(14.dp))
                        SettingsButton("去设置里配置", onClick = onOpenSettings, tag = "ai-open-settings")
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("ai-messages"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (turns.isEmpty()) {
                        item("ai-empty") { AiIntro(onPick = { input = it }) }
                    }
                    items(turns) { turn -> ChatBubble(turn) }
                    progress?.let { text ->
                        item("ai-progress") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Accent))
                                Spacer(Modifier.width(8.dp))
                                Text(text, style = Type.caption, color = InkSecondary, modifier = Modifier.testTag("ai-progress-text"))
                            }
                        }
                    }
                }
                error?.let { message ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Danger.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("ai-error"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, style = Type.caption, color = Danger, modifier = Modifier.weight(1f))
                        if (message.contains("HTTP 40")) {
                            SettingsTextAction("设置", onClick = onOpenSettings)
                        }
                        SettingsTextAction("重试", onClick = {
                            input = turns.lastOrNull { it.role == "user" }?.text.orEmpty()
                            runner.clearError()
                        }, tone = Tone.Danger)
                    }
                }
            }

            Composer(
                value = input,
                onValueChange = { input = it },
                running = running,
                enabled = configured,
                onSend = { send() },
                onStop = { runner.stop() },
            )
            FloatingTabBar(
                selected = 2,
                onSelect = onSelectTab,
                modifier = Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(bottom = 10.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun ConversationManagerDialog(
    conversations: List<AiConversation>,
    currentId: String?,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (AiConversation) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<AiConversation?>(null) }
    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这段对话？", style = Type.headline, color = Ink) },
            text = {
                Text(
                    "「${target.title.ifBlank { "未命名对话" }}」及其中 ${target.turns.size} 条消息会从本机删除，无法恢复。",
                    style = Type.footnote.copy(lineHeight = 20.sp),
                    color = InkSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { pendingDelete = null; onDelete(target) },
                    modifier = Modifier.testTag("ai-chat-delete-confirm"),
                ) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("对话记录", style = Type.headline, color = Ink) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (conversations.isEmpty()) {
                    Text(
                        "还没有对话记录。发送第一条消息后会自动保存在本机；切到别的页面再回来也不会丢。",
                        style = Type.footnote.copy(lineHeight = 20.sp),
                        color = InkSecondary,
                        modifier = Modifier.testTag("ai-chat-empty"),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(conversations, key = { it.id }) { conversation ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (conversation.id == currentId) Accent.copy(alpha = 0.10f)
                                        else Color.Transparent,
                                    )
                                    .clickable { onSelect(conversation.id) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .testTag("ai-chat-row"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        conversation.title.ifBlank { "未命名对话" },
                                        style = Type.footnote.copy(fontWeight = FontWeight.Medium),
                                        color = Ink,
                                        maxLines = 1,
                                    )
                                    Text(
                                        "${conversation.turns.size} 条消息 · ${formatChatTime(conversation.updatedAtMillis)}" +
                                            if (conversation.id == currentId) " · 当前" else "",
                                        style = Type.caption2,
                                        color = InkSecondary,
                                    )
                                }
                                SettingsTextAction(
                                    "删除",
                                    onClick = { pendingDelete = conversation },
                                    tone = Tone.Danger,
                                    tag = "ai-chat-delete",
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingsButton("新建对话", onClick = onNew, tag = "ai-chat-new", style = ActionStyle.Secondary)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

private fun formatChatTime(millis: Long): String {
    if (millis <= 0) return "时间未知"
    return runCatching {
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("")
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SegmentBg)
                .border(1.dp, Accent.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled && !running,
                textStyle = Type.callout.copy(color = Ink),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("ai-input"),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            if (running) "正在处理…" else "问点什么，比如：Kimi 会员现在多少钱？",
                            style = Type.callout,
                            color = InkTertiary,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        if (running) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Danger.copy(alpha = 0.12f))
                    .clickable(onClick = onStop)
                    .testTag("ai-stop"),
                contentAlignment = Alignment.Center,
            ) { Text("停", style = Type.subhead.copy(fontWeight = FontWeight.SemiBold), color = Danger) }
        } else {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Accent else SegmentBg)
                    .clickable(enabled = enabled && value.isNotBlank(), onClick = onSend)
                    .testTag("ai-send"),
                contentAlignment = Alignment.Center,
            ) { Text("发", style = Type.subhead.copy(fontWeight = FontWeight.SemiBold), color = if (enabled) Color.White else InkTertiary) }
        }
    }
}

@Composable
private fun AiIntro(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
            Text("可以直接问，也可以让我去查", style = Type.headline, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "我会读你的本机台账，也能先搜索、再打开官网/网页核对价格。查过的页面会列在回答下面。",
                style = Type.footnote.copy(lineHeight = 20.sp),
                color = InkSecondary,
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                "Kimi 会员连续包月多少钱？和我记的比一下",
                "我今年订阅一共花了多少？",
                "哪条最近要涨价，或者下周就要扣费？",
            ).forEach { sample ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent.copy(alpha = 0.07f))
                        .clickable { onPick(sample) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(sample, style = Type.footnote, color = Ink)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "每次发送会把「台账摘要 + 你的问题」发给你配置的模型服务，可能产生费用；" +
                "需要核对价格时，模型会先把查询词发给公开搜索引擎，再打开它选中的网页；" +
                "网页正文只用于本次回答。对话记录只保存在本机。",
            style = Type.caption.copy(lineHeight = 17.sp),
            color = InkSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    val isUser = turn.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(if (isUser) 0.82f else 0.94f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) Accent else Color.White.copy(alpha = 0.86f))
                .border(
                    1.dp,
                    if (isUser) Accent else Color(0x14000000),
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag(if (isUser) "ai-user-bubble" else "ai-assistant-bubble"),
        ) {
            Text(
                turn.text,
                style = Type.callout.copy(lineHeight = 22.sp),
                color = if (isUser) Color.White else Ink,
            )
            if (turn.citations.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("查过的页面", style = Type.caption2.copy(fontWeight = FontWeight.Medium), color = InkSecondary)
                turn.citations.take(6).forEach { url ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hostOf(url) + "  " + url,
                        style = Type.caption.copy(lineHeight = 16.sp),
                        color = Accent,
                        modifier = Modifier.testTag("ai-citation"),
                    )
                }
            }
        }
    }
}
