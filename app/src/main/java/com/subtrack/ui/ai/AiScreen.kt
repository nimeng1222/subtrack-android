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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.BuildConfig
import com.subtrack.SubTrackApp
import com.subtrack.ai.AiChatEngine
import com.subtrack.ai.ChatTurn
import com.subtrack.ai.LedgerDigest
import com.subtrack.domain.model.AppSettings
import com.subtrack.network.NetworkTransport
import com.subtrack.network.SecretStore
import com.subtrack.ui.network.AiPriceDialog
import com.subtrack.ui.network.PriceRetrievalDialog
import com.subtrack.ui.network.SearchDialog
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.home.FloatingTabBar
import com.subtrack.ui.settings.ActionStyle
import com.subtrack.ui.settings.SettingsButton
import com.subtrack.ui.settings.SettingsTextAction
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
 * 它能做三件事：读本机台账回答问题、自己打开官网/网页核对价格、把两边放在一起比较。
 *
 * 每次发送都会把「台账摘要 + 你的问题」发给你配置的模型服务；抓取的网页正文只用于本次回答，
 * 不写入台账、不落盘。等结果期间可以随时停止，迟到的响应不会再回到对话里。
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

    var turns by remember { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    // 旧的三件工具（粘贴材料核对 / 公开资料 / 引证检索）保留下来，作为 AI 页的次级入口，
    // 不再散落在设置页里和模型配置混在一起。
    var toolOpen by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    // 已保存的来源变了（换地址 / 关开关 / 清除连接）：取消在途请求，迟到的回答不能再回到对话里。
    var sourceKey by remember {
        mutableStateOf("${settings.network.aiBaseUrl}#${settings.network.connectionRevision}#${settings.network.aiEnabled}")
    }
    LaunchedEffect(settings.network.aiBaseUrl, settings.network.connectionRevision, settings.network.aiEnabled) {
        val current = "${settings.network.aiBaseUrl}#${settings.network.connectionRevision}#${settings.network.aiEnabled}"
        if (current != sourceKey) {
            job?.cancel()
            sourceKey = current
            error = null
            progress = null
        }
    }

    val configured = settings.network.aiEnabled && settings.network.aiBaseUrl.isNotBlank()
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, progress) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size)
    }

    fun send() {
        val question = input.trim()
        if (question.isEmpty() || running) return
        if (!configured) { onOpenSettings(); return }
        val endpoint = settings.network.aiBaseUrl
        val model = settings.network.aiModel
        val history = turns
        val sourceAtSend = sourceKey
        turns = history + ChatTurn("user", question)
        input = ""
        error = null
        running = true
        progress = null
        job = scope.launch {
            val citations = mutableListOf<String>()
            try {
                val key = withContextIo { secrets.getBound(endpoint) }
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
                    AiChatEngine.systemPrompt(today.toString()) + "\n\n" + digest,
                    history,
                )
                messages += AiChatEngine.userMessage(question)
                var fetches = 0
                var answer = ""
                for (round in 0 until AiChatEngine.MAX_TOOL_ROUNDS) {
                    val body = AiChatEngine.requestBody(model, messages)
                    // 工具循环会在几秒内连续请求同一个地址；这里不能用"每地址 2 秒"的限流，
                    // 否则第二轮必然被打成"请求过于频繁"。轮次上限由 MAX_TOOL_ROUNDS 控制。
                    val response = transport.request(
                        endpoint, body, key, maxBytes = 262_144, maxRequestBytes = 262_144,
                        throttle = false,
                    ).toString(Charsets.UTF_8)
                    when (val step = AiChatEngine.parseStep(response, citations)) {
                        is AiChatEngine.Step.Answer -> { answer = step.text; break }
                        is AiChatEngine.Step.Tools -> {
                            messages += step.assistantMessage
                            for (call in step.calls) {
                                when (call.name) {
                                    "fetch_page" -> {
                                        if (fetches >= AiChatEngine.MAX_FETCHES) {
                                            messages += AiChatEngine.toolResultMessage(call.id, "{\"error\":\"本轮抓取次数已达上限\"}")
                                            continue
                                        }
                                        val url = runCatching {
                                            com.subtrack.network.parseObject(call.arguments).fields["url"]
                                        }.getOrNull()
                                        val target = (url as? com.subtrack.data.backup.JsonValue.Str)?.value.orEmpty()
                                        if (target.isBlank()) {
                                            messages += AiChatEngine.toolResultMessage(call.id, "{\"error\":\"缺少 url\"}")
                                            continue
                                        }
                                        fetches++
                                        progress = "正在打开 ${hostOf(target)}…"
                                        val (payload, failed) = AiChatEngine.fetchPage(transport, target)
                                        if (!failed) citations += target
                                        messages += AiChatEngine.toolResultMessage(call.id, payload)
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
                                    else -> messages += AiChatEngine.toolResultMessage(call.id, "{\"error\":\"未知工具\"}")
                                }
                            }
                            progress = if (round + 1 >= AiChatEngine.MAX_TOOL_ROUNDS) "整理回答…" else "继续查资料…"
                        }
                    }
                }
                if (answer.isBlank()) {
                    throw IllegalStateException("查了 $fetches 个页面还没整理出答案，再说一次或换个问法试试")
                }
                // 来源已经换了：这条回答属于旧来源，直接丢弃（保留用户刚问的问题）。
                if (sourceAtSend != sourceKey) return@launch
                turns = turns + ChatTurn("assistant", answer, citations.distinct())
            } catch (e: CancellationException) {
                // 用户点"停"才留一句"已停止"；如果是换了来源/关了开关导致的取消，静默丢弃，
                // 否则旧来源的残留会以"已停止"的形式出现在新来源的对话里。
                if (sourceAtSend == sourceKey) {
                    turns = turns + ChatTurn("assistant", "已停止。上面已经查到的内容仍然保留。", failed = true)
                }
                throw e
            } catch (e: Exception) {
                error = e.message?.take(200) ?: "请求失败"
            } finally {
                running = false
                progress = null
            }
        }
    }

    AiLegacyTools(settings, toolOpen, onClose = { toolOpen = null })

    AmbientBackground {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 助手", style = Type.title2, color = Ink, modifier = Modifier.weight(1f))
                    if (turns.isNotEmpty()) {
                        SettingsTextAction("清空", onClick = { turns = emptyList(); error = null }, enabled = !running)
                    }
                }
                if (configured) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SettingsTextAction("粘贴材料核对", onClick = { toolOpen = "material" }, enabled = !running)
                        SettingsTextAction("查公开资料", onClick = { toolOpen = "search" }, enabled = !running)
                        SettingsTextAction("引证检索", onClick = { toolOpen = "citations" }, enabled = !running)
                    }
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
                        SettingsTextAction("重试", onClick = {
                            input = turns.lastOrNull { it.role == "user" }?.text.orEmpty()
                            error = null
                        }, tone = com.subtrack.ui.settings.Tone.Danger)
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
private fun AiLegacyTools(settings: AppSettings, tool: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val transport = remember { NetworkTransport(BuildConfig.DEBUG) }
    val secrets = remember { SecretStore(context) }
    when (tool) {
        "material" -> AiPriceDialog(settings, transport, secrets, onDismiss = onClose)
        "search" -> SearchDialog(onClose = onClose)
        "citations" -> PriceRetrievalDialog(settings, onClose = onClose)
    }
}

private suspend fun <T> withContextIo(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

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
                            if (running) "正在处理…" else "问点什么，比如：Netflix 现在多少钱？",
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
                "我会读你的本机台账，也能打开官网/网页核对价格。查过的页面会列在回答下面。",
                style = Type.footnote.copy(lineHeight = 20.sp),
                color = InkSecondary,
            )
            Spacer(Modifier.height(12.dp))
            listOf(
                "Netflix 标准版现在多少钱？和我记的比一下",
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
            "每次发送会把「台账摘要 + 你的问题」发给你配置的模型服务，可能产生费用；网页正文只用于本次回答。",
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
