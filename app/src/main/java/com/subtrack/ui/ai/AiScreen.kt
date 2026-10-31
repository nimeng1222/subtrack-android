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
import androidx.compose.runtime.DisposableEffect
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
import com.subtrack.BuildConfig
import com.subtrack.SubTrackApp
import com.subtrack.ai.AiChatEngine
import com.subtrack.ai.AiConversation
import com.subtrack.ai.ChatTurn
import com.subtrack.ai.LedgerDigest
import com.subtrack.ai.WebSearch
import com.subtrack.data.backup.JsonValue
import com.subtrack.data.backup.render
import com.subtrack.domain.model.AppSettings
import com.subtrack.network.NetworkTransport
import com.subtrack.network.SecretStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

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
 * 不写入台账。等结果期间可以随时停止，迟到的响应不会再回到对话里。
 */
@Composable
fun AiScreen(
    settings: AppSettings,
    subscriptions: List<com.subtrack.domain.model.Subscription>,
    today: LocalDate,
    onOpenSettings: () -> Unit,
    onSelectTab: (Int) -> Unit,
    /** 切换当前使用的模型（设置里勾选过的那些）。 */
    onSelectModel: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as SubTrackApp).container }
    val transport = remember { NetworkTransport(BuildConfig.DEBUG) }
    val secrets = remember { SecretStore(context) }
    val chatStore = container.aiChats
    val chatState by chatStore.state.collectAsState()
    val conversations = chatState.conversations
    val currentConversation = conversations.firstOrNull { it.id == chatState.currentId }
    val turns = currentConversation?.turns.orEmpty()

    LaunchedEffect(Unit) { chatStore.ensureLoaded() }

    // 近期/本月实付：这里自己算一次，避免把统计参数从 MainActivity 一路传进来。
    var recentPaidMinor by remember { mutableStateOf<Long?>(null) }
    var monthPaidMinor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(subscriptions.size, today, settings.multiCurrencyEnabled, settings.effectiveFxRates) {
        val fx = if (settings.multiCurrencyEnabled) settings.effectiveFxRates else null
        withContextIo {
            val recent = runCatching {
                com.subtrack.domain.stats.CurrencyConverter
                    .paymentsCnySummary(container.repository.paymentsBetween(today.minusDays(90), today), fx).first
            }.getOrNull()
            val month = runCatching {
                com.subtrack.domain.stats.CurrencyConverter
                    .paymentsCnySummary(container.repository.paymentsBetween(today.withDayOfMonth(1), today), fx).first
            }.getOrNull()
            recentPaidMinor = recent
            monthPaidMinor = month
        }
    }

    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showChats by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    // 已保存的来源变了（换地址 / 关开关 / 清除连接）：取消在途请求，迟到的回答不能再回到对话里。
    var sourceKey by remember {
        mutableStateOf("${settings.network.aiBaseUrl}#${settings.network.connectionRevision}#${settings.network.aiEnabled}")
    }
    LaunchedEffect(settings.network.aiBaseUrl, settings.network.connectionRevision, settings.network.aiEnabled) {
        val current = "${settings.network.aiBaseUrl}#${settings.network.connectionRevision}#${settings.network.aiEnabled}"
        if (current != sourceKey) {
            // 先更新来源键再取消：取消是异步的，这样 catch 里一定能判断出"是换来源导致的取消"，
            // 不会把旧来源的残留以"已停止"写进新来源的对话。
            sourceKey = current
            job?.cancel()
            error = null
            progress = null
        }
    }

    val configured = settings.network.aiEnabled && settings.network.aiBaseUrl.isNotBlank()
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, progress) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size)
    }

    fun startNewConversation() {
        if (running) return
        job?.cancel()
        error = null
        progress = null
        input = ""
        showChats = false
        scope.launch { chatStore.startDraft() }
    }

    fun send() {
        val question = input.trim()
        if (question.isEmpty() || running) return
        if (!configured) { onOpenSettings(); return }
        // 用户可能只填到 /v1：模型列表能取到，但对话会 POST 到前缀本身而被拒。
        // 发送时补全成 /chat/completions，不改已保存的值，也不动密钥绑定。
        val savedUrl = settings.network.aiBaseUrl
        val endpoint = com.subtrack.ai.AiEndpoint.chatUrl(savedUrl)
        val model = settings.network.aiModel
        val sourceAtSend = sourceKey
        input = ""
        error = null
        running = true
        progress = null
        job = scope.launch {
            val citations = mutableListOf<String>()
            val searched = mutableListOf<String>()
            val attempts = mutableListOf<AiChatEngine.PageAttempt>()
            val attemptedUrls = mutableSetOf<String>()
            var fetches = 0
            var searches = 0
            var conversationId = ""
            try {
                // 先从磁盘恢复，确保 send 发生在首帧加载之前时也能拿到正确的历史。
                chatStore.ensureLoaded()
                val history = chatStore.state.value.conversations
                    .firstOrNull { it.id == chatStore.state.value.currentId }
                    ?.turns.orEmpty()
                // 先落盘用户消息：即使随后切 Tab 或被系统杀掉，回来也能看到自己问过什么。
                conversationId = chatStore.beginTurn(question)
                val key = withContextIo { secrets.getBound(savedUrl) }
                require(!key.isNullOrBlank()) { "还没有保存这个地址的密钥，去「设置 → 联网与 AI」里保存" }
                val digest = withContextIo {
                    LedgerDigest.build(
                        subscriptions = container.repository.observeAllSnapshot(),
                        today = today,
                        recentPaidMinor = recentPaidMinor,
                        monthPaidMinor = monthPaidMinor,
                    )
                }
                val messages = AiChatEngine.initialMessages(
                    AiChatEngine.systemPrompt(today.toString(), tools = true) + "\n\n" + digest,
                    history,
                )
                messages += AiChatEngine.userMessage(question)
                var answer = ""
                var useTools = true
                var finalPromptSent = false
                var round = 0
                // 工具阶段的时间上限：单次请求最长 25 秒，8 次抓取 + 3 次搜索足够；
                // 超过 90 秒就停止继续查，转入"整理回答"，避免用户对着转圈等好几分钟。
                val toolDeadlineMillis = System.currentTimeMillis() + 90_000
                // 多留一轮：最后一轮不带工具，专门把已经查到的材料整理成回答。
                val maxRequests = AiChatEngine.MAX_TOOL_ROUNDS + 1
                while (round < maxRequests) {
                    val toolsAllowed = useTools &&
                        round < AiChatEngine.MAX_TOOL_ROUNDS &&
                        fetches < AiChatEngine.MAX_FETCHES &&
                        System.currentTimeMillis() < toolDeadlineMillis
                    if (!toolsAllowed && useTools && !finalPromptSent) {
                        messages += AiChatEngine.systemMessage(AiChatEngine.FINAL_ANSWER_PROMPT)
                        finalPromptSent = true
                        progress = "整理已查到的资料…"
                    }
                    val body = AiChatEngine.requestBody(model, messages, includeTools = toolsAllowed)
                    // 工具循环会在几秒内连续请求同一个地址；这里不能用"每地址 2 秒"的限流，
                    // 否则第二轮必然被打成"请求过于频繁"。轮次上限由 MAX_TOOL_ROUNDS 控制。
                    val response = try {
                        transport.request(
                            endpoint, body, key, maxBytes = 262_144, maxRequestBytes = 262_144,
                            throttle = false, userAgent = "SubTrack-Android/${BuildConfig.VERSION_NAME}",
                        ).toString(Charsets.UTF_8)
                    } catch (e: com.subtrack.network.HttpStatusException) {
                        // 有些服务对"不支持 tool calling 的模型"不回 400 而是 403。第一轮就退化成
                        // 普通对话再试一次；还失败就把服务端原文交给用户，而不是只留一句 HTTP 403。
                        if (useTools && toolsAllowed && round == 0 &&
                            com.subtrack.ai.AiChatFallback.shouldRetryWithoutTools(e.code, e.detail)
                        ) {
                            useTools = false
                            messages[0] = AiChatEngine.systemMessage(
                                AiChatEngine.systemPrompt(today.toString(), tools = false) + "\n\n" + digest,
                            )
                            progress = "这个服务不支持工具调用，改为普通对话…"
                            round++
                            continue
                        }
                        throw e
                    }
                    val step = try {
                        AiChatEngine.parseStep(response, citations)
                    } catch (e: Exception) {
                        // 收尾那一轮即使模型回空，也走下面的"可操作说明"，不留一句裸错误。
                        if (!toolsAllowed) break else throw e
                    }
                    when (step) {
                        is AiChatEngine.Step.Answer -> { answer = step.text; break }
                        is AiChatEngine.Step.Tools -> {
                            if (!toolsAllowed) {
                                // 已经明确不给工具了，模型却仍返回 tool_calls：有文字就采用，没有就收尾。
                                if (step.content.isNotBlank()) answer = step.content
                                break
                            }
                            messages += step.assistantMessage
                            for (call in step.calls) {
                                when (call.name) {
                                    "search_web" -> {
                                        val query = toolArgument(call.arguments, "query")
                                        when {
                                            query.isBlank() ->
                                                messages += AiChatEngine.toolResultMessage(
                                                    call.id, toolError("缺少 query"),
                                                )
                                            searches >= AiChatEngine.MAX_SEARCHES ->
                                                messages += AiChatEngine.toolResultMessage(
                                                    call.id,
                                                    toolError("本轮搜索次数已达上限，请直接打开已知网页或基于已有资料回答"),
                                                )
                                            else -> {
                                                searches++
                                                searched += query
                                                progress = "正在搜索：${query.take(24)}…"
                                                val outcome = WebSearch.search(transport, query)
                                                messages += AiChatEngine.toolResultMessage(
                                                    call.id,
                                                    AiChatEngine.searchResultPayload(query, outcome),
                                                )
                                            }
                                        }
                                    }
                                    "fetch_page" -> {
                                        val target = toolArgument(call.arguments, "url")
                                        when {
                                            target.isBlank() ->
                                                messages += AiChatEngine.toolResultMessage(
                                                    call.id, toolError("缺少 url"),
                                                )
                                            target in attemptedUrls ->
                                                messages += AiChatEngine.toolResultMessage(
                                                    call.id,
                                                    toolError("这个地址本轮已经打开过，请直接使用上面的正文，或换一个来源"),
                                                )
                                            fetches >= AiChatEngine.MAX_FETCHES ->
                                                messages += AiChatEngine.toolResultMessage(
                                                    call.id,
                                                    toolError("本轮打开页面的次数已达上限，请基于已有资料回答"),
                                                )
                                            else -> {
                                                attemptedUrls += target
                                                fetches++
                                                progress = "正在打开 ${hostOf(target)}…"
                                                val page = AiChatEngine.fetchPage(transport, target)
                                                attempts += AiChatEngine.PageAttempt(page.url, page.ok, page.reason)
                                                if (page.ok) citations += target
                                                messages += AiChatEngine.toolResultMessage(call.id, page.payload)
                                            }
                                        }
                                    }
                                    "read_ledger" -> {
                                        progress = "正在读本机台账…"
                                        messages += AiChatEngine.toolResultMessage(call.id, LedgerDigest.build(
                                            subscriptions = container.repository.observeAllSnapshot(),
                                            today = today,
                                            recentPaidMinor = recentPaidMinor,
                                            monthPaidMinor = monthPaidMinor,
                                        ))
                                    }
                                    else -> messages += AiChatEngine.toolResultMessage(call.id, toolError("未知工具"))
                                }
                            }
                            progress = if (fetches >= AiChatEngine.MAX_FETCHES) "整理回答…" else "继续查资料…"
                        }
                    }
                    round++
                }
                if (answer.isBlank()) {
                    // 不再只给一句"换个问法试试"：把检索词、成功/失败页面和下一步建议交给用户。
                    if (sourceAtSend != sourceKey) return@launch
                    chatStore.appendTurn(
                        conversationId,
                        ChatTurn("assistant", AiChatEngine.failureNote(searched, attempts), citations.distinct(), failed = true),
                    )
                    return@launch
                }
                // 来源已经换了：这条回答属于旧来源，直接丢弃（保留用户刚问的问题）。
                if (sourceAtSend != sourceKey) return@launch
                chatStore.appendTurn(conversationId, ChatTurn("assistant", answer, citations.distinct()))
            } catch (e: CancellationException) {
                // 用户点"停"才留一句"已停止"；如果是换了来源/关了开关导致的取消，静默丢弃，
                // 否则旧来源的残留会以"已停止"的形式出现在新来源的对话里。
                // 协程已被取消，落盘必须放在 NonCancellable 里，否则 appendTurn 会在第一个挂起点直接抛取消。
                if (sourceAtSend == sourceKey && conversationId.isNotBlank()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        chatStore.appendTurn(
                            conversationId,
                            ChatTurn("assistant", "已停止。上面已经查到的内容仍然保留。", failed = true),
                        )
                    }
                }
                throw e
            } catch (e: Exception) {
                val message = e.message?.take(300) ?: "请求失败"
                error = message
                // 错误条只存在于当前页面；把失败原因也写进对话，回到这里时不会只剩一个没有回答的问题。
                if (conversationId.isNotBlank() && sourceAtSend == sourceKey) {
                    runCatching {
                        chatStore.appendTurn(
                            conversationId,
                            ChatTurn("assistant", "这次没有完成：$message", failed = true),
                        )
                    }
                }
            } finally {
                running = false
                progress = null
            }
        }
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
                if (conversation.id == chatState.currentId) job?.cancel()
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
                            error = null
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
                onStop = { job?.cancel() },
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

private suspend fun <T> withContextIo(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

private fun toolArgument(arguments: String, key: String): String = runCatching {
    (com.subtrack.network.parseObject(arguments).fields[key] as? JsonValue.Str)?.value.orEmpty()
}.getOrDefault("")

private fun toolError(message: String): String =
    JsonValue.Obj(mapOf("error" to JsonValue.Str(message))).render()

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
