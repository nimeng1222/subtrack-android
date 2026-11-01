package com.subtrack.ai

import com.subtrack.BuildConfig
import com.subtrack.data.backup.JsonValue
import com.subtrack.data.backup.render
import com.subtrack.domain.model.AppSettings
import com.subtrack.network.HttpStatusException
import com.subtrack.network.NetworkTransport
import com.subtrack.network.parseObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.time.LocalDate

/** 一次生成的可见状态。由应用级 Runner 持有，AI 页销毁/重建不影响任务本身。 */
data class AiRunState(
    val running: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val conversationId: String = "",
)

/** Runner 需要的对话存储能力；单测用内存实现替代 [AiChatStore]。 */
interface AiChatSink {
    val state: StateFlow<AiChatState>
    suspend fun ensureLoaded()
    suspend fun beginTurn(question: String): String
    suspend fun appendTurn(conversationId: String, turn: ChatTurn)
}

/** 只发一次对话请求的最小抽象，便于单测注入假实现（真实实现走 [NetworkTransport]）。 */
fun interface AiModelTransport {
    suspend fun request(endpoint: String, body: String, key: String): String
}

/**
 * AI 对话生成器（1.9.8）。
 *
 * 原来的生成协程挂在 [AiScreen] 的 `rememberCoroutineScope()` 上：BottomBar 切到「订阅/统计」时
 * `ScreenRouter` 的 `when(tab)` 会让 AI 页离开组合，scope 随之取消，生成被打断并写下"已停止"。
 * 现在任务跑在 AppContainer 持有的应用级 [scope] 上：
 * - 切页/页面重建不取消任务，回到 AI 页时从 [state] 与 AiChatStore 重新挂接；
 * - 用户点「停止」、换对话/删对话、或更换已保存的模型来源时才取消；
 * - 进程被系统杀掉后任务仍会中断（没有前台服务，这是明确取舍）。
 *
 * 超时口径：
 * - 模型请求由调用方注入的 [modelTransport] 决定（真实实现是 90 秒总超时 / 60 秒读超时，
 *   见 AppContainer），而不是旧实现的 25 秒总超时 / 15 秒读超时——非流式回答要等服务端
 *   生成完才回第一个字节，15 秒读超时会把正常的长回答误判成 timeout。
 * - 连接类超时（10 秒内失败）自动重试一次；读超时不重试，避免让用户多等一倍。
 * - 超时后若有已打开的来源，会把来源写进失败消息，不假装成功。
 */
class AiChatRunner(
    private val scope: CoroutineScope,
    private val chatStore: AiChatSink,
    private val transport: NetworkTransport,
    private val modelTransport: AiModelTransport,
    private val settingsProvider: () -> AppSettings,
    private val keyProvider: suspend (url: String) -> String?,
    private val todayProvider: () -> LocalDate,
    private val digestProvider: suspend (LocalDate) -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(AiRunState())
    val state: StateFlow<AiRunState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var runningSourceKey: String? = null

    fun sourceKey(settings: AppSettings = settingsProvider()): String =
        "${settings.network.aiBaseUrl}#${settings.network.connectionRevision}#${settings.network.aiEnabled}"

    /** 发送一条问题。生成跑在应用级 scope 上，调用方（Composable）被销毁不会取消它。 */
    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _state.value.running) return
        val settings = settingsProvider()
        if (!settings.network.aiEnabled || settings.network.aiBaseUrl.isBlank()) return
        val savedUrl = settings.network.aiBaseUrl
        val endpoint = AiEndpoint.chatUrl(savedUrl)
        val model = settings.network.aiModel
        val sourceAtSend = sourceKey(settings)
        runningSourceKey = sourceAtSend
        _state.value = AiRunState(running = true)

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
                conversationId = chatStore.beginTurn(text)
                _state.value = _state.value.copy(conversationId = conversationId)
                val key = keyProvider(savedUrl)
                require(!key.isNullOrBlank()) { "还没有保存这个地址的密钥，去「设置 → 联网与 AI」里保存" }
                val today = todayProvider()
                val digest = digestProvider(today)
                // 台账类问题只给它 read_ledger，避免为了"我花了多少钱"再去联网搜索。
                val ledgerOnly = AiChatEngine.looksLikeLedgerQuestion(text)
                val systemText = AiChatEngine.systemPrompt(today.toString(), tools = true) +
                    "\n\n" + digest + if (ledgerOnly) {
                    "\n\n本轮只涉及用户自己的台账：直接调用 read_ledger 回答，不要联网搜索或打开网页。"
                } else {
                    ""
                }
                val messages = AiChatEngine.initialMessages(systemText, history)
                messages += AiChatEngine.userMessage(text)
                var answer = ""
                var useTools = true
                var finalPromptSent = false
                var round = 0
                // 工具阶段的时间上限：单次请求最长 90 秒；超过 90 秒就停止继续查，转入"整理回答"。
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
                        progress("整理已查到的资料…")
                    }
                    val body = AiChatEngine.requestBody(
                        model, messages, includeTools = toolsAllowed, ledgerOnly = ledgerOnly,
                    )
                    val response = try {
                        callModel(endpoint, body, key)
                    } catch (e: HttpStatusException) {
                        // 有些服务对"不支持 tool calling 的模型"不回 400 而是 403。第一轮就退化成
                        // 普通对话再试一次；还失败就把服务端原文交给用户，而不是只留一句 HTTP 403。
                        if (useTools && toolsAllowed && round == 0 &&
                            AiChatFallback.shouldRetryWithoutTools(e.code, e.detail)
                        ) {
                            useTools = false
                            messages[0] = AiChatEngine.systemMessage(
                                AiChatEngine.systemPrompt(today.toString(), tools = false) + "\n\n" + digest,
                            )
                            progress("这个服务不支持工具调用，改为普通对话…")
                            round++
                            continue
                        }
                        throw e
                    }
                    val step = try {
                        AiChatEngine.parseStep(response, citations)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // 收尾那一轮即使模型回空，也走下面的"可操作说明"，不留一句裸错误。
                        if (!toolsAllowed) break else throw e
                    }
                    when (step) {
                        is AiChatEngine.Step.Answer -> {
                            answer = step.text
                            break
                        }

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
                                                progress("正在搜索：${query.take(24)}…")
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
                                                progress("正在打开 ${hostOf(target)}…")
                                                val page = AiChatEngine.fetchPage(transport, target)
                                                attempts += AiChatEngine.PageAttempt(page.url, page.ok, page.reason)
                                                if (page.ok) citations += target
                                                messages += AiChatEngine.toolResultMessage(call.id, page.payload)
                                            }
                                        }
                                    }

                                    "read_ledger" -> {
                                        progress("正在读本机台账…")
                                        messages += AiChatEngine.toolResultMessage(call.id, digest)
                                    }

                                    else -> messages += AiChatEngine.toolResultMessage(call.id, toolError("未知工具"))
                                }
                            }
                            progress(
                                if (fetches >= AiChatEngine.MAX_FETCHES) "整理回答…" else "继续查资料…",
                            )
                        }
                    }
                    round++
                }
                if (answer.isBlank()) {
                    // 不再只给一句"换个问法试试"：把检索词、成功/失败页面和下一步建议交给用户。
                    if (sourceChanged(sourceAtSend)) return@launch
                    chatStore.appendTurn(
                        conversationId,
                        ChatTurn("assistant", AiChatEngine.failureNote(searched, attempts), citations.distinct(), failed = true),
                    )
                    return@launch
                }
                // 来源已经换了：这条回答属于旧来源，直接丢弃（保留用户刚问的问题）。
                if (sourceChanged(sourceAtSend)) return@launch
                chatStore.appendTurn(conversationId, ChatTurn("assistant", answer, citations.distinct()))
            } catch (e: TimeoutCancellationException) {
                // 请求自身的超时不是"用户点停"：要给出可辨认的失败，而不是写成“已停止”。
                finishFailure(
                    conversationId, sourceAtSend,
                    timeoutMessage(citations), citations,
                )
            } catch (e: CancellationException) {
                // 用户点"停"才留一句"已停止"；如果是换了来源/关了开关导致的取消，静默丢弃，
                // 否则旧来源的残留会以"已停止"的形式出现在新来源的对话里。
                if (!sourceChanged(sourceAtSend) && conversationId.isNotBlank()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        chatStore.appendTurn(
                            conversationId,
                            ChatTurn("assistant", "已停止。上面已经查到的内容仍然保留。", failed = true),
                        )
                    }
                }
                throw e
            } catch (e: Exception) {
                val message = describeError(e)
                finishFailure(conversationId, sourceAtSend, message, citations)
            } finally {
                runningSourceKey = null
                _state.value = _state.value.copy(running = false, progress = null)
                job = null
            }
        }
    }

    /** 用户主动停止。 */
    fun stop() {
        job?.cancel()
    }

    /** 删除正在生成的对话时才需要停；其余删除不打断别的对话。 */
    fun stopIf(conversationId: String) {
        if (_state.value.conversationId == conversationId) job?.cancel()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** 已保存的模型来源变了（换地址/关开关）：取消在途请求，迟到的回答不能再回到对话里。 */
    fun onSourceChanged() {
        val current = sourceKey()
        if (current != runningSourceKey) {
            job?.cancel()
            _state.value = _state.value.copy(error = null, progress = null)
        }
    }

    private fun sourceChanged(sourceAtSend: String): Boolean = sourceKey() != sourceAtSend

    private fun progress(text: String?) {
        _state.value = _state.value.copy(progress = text)
    }

    /**
     * 模型请求：连接类超时（10 秒内失败）重试一次；读超时不重试。
     * 非流式响应要等服务端生成完才回第一个字节，60 秒读超时是正常长回答的上限。
     */
    private suspend fun callModel(endpoint: String, body: String, key: String): String {
        val started = nowMillis()
        return try {
            modelTransport.request(endpoint, body, key)
        } catch (e: SocketTimeoutException) {
            val elapsed = nowMillis() - started
            if (elapsed < CONNECT_RETRY_WINDOW_MS) {
                modelTransport.request(endpoint, body, key)
            } else {
                throw e
            }
        }
    }

    private suspend fun finishFailure(
        conversationId: String,
        sourceAtSend: String,
        message: String,
        citations: List<String>,
    ) {
        if (conversationId.isBlank() || sourceChanged(sourceAtSend)) {
            _state.value = _state.value.copy(error = message)
            return
        }
        val text = buildString {
            append("这次没有完成：").append(message)
            if (citations.isNotEmpty()) {
                append("。已经打开过的页面：")
                append(citations.distinct().take(3).joinToString("、") { hostOf(it) })
            }
        }
        _state.value = _state.value.copy(error = message)
        runCatching {
            chatStore.appendTurn(
                conversationId,
                ChatTurn("assistant", text, citations.distinct(), failed = true),
            )
        }
    }

    private fun timeoutMessage(citations: List<String>): String =
        "模型服务响应超时（单次请求最长 90 秒）" +
            if (citations.isEmpty()) "，请重试" else ""

    private fun describeError(e: Exception): String = when (e) {
        is SocketTimeoutException -> "模型服务响应超时（timeout），请重试"
        else -> e.message?.take(300) ?: "请求失败"
    }

    private fun toolArgument(arguments: String, key: String): String = runCatching {
        (parseObject(arguments).fields[key] as? JsonValue.Str)?.value.orEmpty()
    }.getOrDefault("")

    private fun toolError(message: String): String =
        JsonValue.Obj(mapOf("error" to JsonValue.Str(message))).render()

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    companion object {
        /** 这么快就失败的，基本是连接阶段超时，重试一次成本低。 */
        private const val CONNECT_RETRY_WINDOW_MS = 10_000L

        const val MODEL_TOTAL_TIMEOUT_MS = 90_000L
        const val MODEL_CONNECT_TIMEOUT_MS = 10_000
        const val MODEL_READ_TIMEOUT_MS = 60_000
    }
}
