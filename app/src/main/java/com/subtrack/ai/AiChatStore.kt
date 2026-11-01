package com.subtrack.ai

import android.content.Context
import android.util.AtomicFile
import com.subtrack.data.backup.JsonParser
import com.subtrack.data.backup.JsonValue
import com.subtrack.data.backup.asObject
import com.subtrack.data.backup.render
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 一段本地对话。标题取第一条用户提问，用于对话列表。 */
data class AiConversation(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val turns: List<ChatTurn> = emptyList(),
)

/** 对话列表 + 当前选中的对话；[currentId] 为 null 表示"新对话草稿"（还没发消息）。 */
data class AiChatState(
    val conversations: List<AiConversation> = emptyList(),
    val currentId: String? = null,
)

/**
 * AI 对话的本地持久化。
 *
 * 原实现把 `turns` 放在 `AiScreen` 的 `remember` 里：底部切到「订阅」或「统计」时
 * `ScreenRouter` 走的是 `when(tab)`，AI 页离开组合，`remember` 随之销毁，历史就没了。
 * 这里把对话放到应用级单例（由 AppContainer 持有），并原子写入应用私有目录：
 * - 切 tab / 息屏 / 杀进程后回来都能恢复；
 * - 纯本地文件，不上传、不需要账号，符合本地优先定位；
 * - 最多保留 [MAX_CONVERSATIONS] 段、每段 [MAX_TURNS] 条，避免无限增长。
 *
 * 文件用 [AtomicFile]，写一半被系统杀掉也不会留下半截 JSON。
 */
class AiChatStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AiChatSink {
    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AiChatState())
    override val state: StateFlow<AiChatState> = _state.asStateFlow()

    @Volatile
    private var loaded = false

    /** 首次进入 AI 页时从磁盘恢复；之后直接读内存中的 StateFlow。 */
    override suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val restored = withContext(Dispatchers.IO) { readFromDisk() }
            _state.value = normalize(restored ?: AiChatState())
            loaded = true
        }
    }

    /**
     * 开始一轮提问：没有当前对话就新建一段（标题取问题本身），否则追加到当前对话。
     * 返回对话 id；返回时用户消息已经落盘，即使随后切走页面也不会丢。
     */
    override suspend fun beginTurn(question: String): String {
        ensureLoaded()
        var resultId = ""
        mutex.withLock {
            val state = _state.value
            val now = nowMillis()
            val current = state.conversations.firstOrNull { it.id == state.currentId }
            val next = if (current != null) {
                resultId = current.id
                val updated = current.copy(
                    turns = current.turns + ChatTurn("user", question),
                    updatedAtMillis = now,
                )
                state.copy(
                    conversations = state.conversations.map { if (it.id == current.id) updated else it },
                )
            } else {
                val created = AiConversation(
                    id = newId(),
                    title = titleFor(question),
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    turns = listOf(ChatTurn("user", question)),
                )
                resultId = created.id
                state.copy(
                    conversations = listOf(created) + state.conversations,
                    currentId = created.id,
                )
            }
            val normalized = normalize(next)
            _state.value = normalized
            persist(normalized)
        }
        return resultId
    }

    /** 追加模型的回答/停止提示；对话已被删除时静默忽略，避免把答案写回一段不存在的记录。 */
    override suspend fun appendTurn(conversationId: String, turn: ChatTurn) {
        ensureLoaded()
        mutate { state ->
            if (state.conversations.none { it.id == conversationId }) return@mutate state
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == conversationId) {
                        it.copy(turns = it.turns + turn, updatedAtMillis = nowMillis())
                    } else {
                        it
                    }
                },
            )
        }
    }

    suspend fun select(conversationId: String) {
        ensureLoaded()
        mutate { state ->
            if (state.conversations.none { it.id == conversationId }) state
            else state.copy(currentId = conversationId)
        }
    }

    /** "新建对话"：不立刻创建空记录，只把当前指针清空；发送第一条消息时才落库。 */
    suspend fun startDraft() {
        ensureLoaded()
        mutate { it.copy(currentId = null) }
    }

    /** 删除一段对话；删的是当前对话时自动切到最近的一段，没有就回到草稿状态。 */
    suspend fun delete(conversationId: String) {
        ensureLoaded()
        mutate { state ->
            val remaining = state.conversations.filterNot { it.id == conversationId }
            state.copy(
                conversations = remaining,
                currentId = if (state.currentId == conversationId) {
                    remaining.firstOrNull()?.id
                } else {
                    state.currentId
                },
            )
        }
    }

    private suspend fun mutate(change: (AiChatState) -> AiChatState) {
        ensureLoaded()
        mutex.withLock {
            val next = normalize(change(_state.value))
            if (next == _state.value) return
            _state.value = next
            persist(next)
        }
    }

    /** 排重、排序、裁剪；任何一步都不应让坏数据把整段历史拖没。 */
    private fun normalize(input: AiChatState): AiChatState {
        val seen = mutableSetOf<String>()
        val candidates = input.conversations.asSequence()
            .mapNotNull { conversation ->
                val id = conversation.id.trim()
                if (id.isEmpty() || !seen.add(id)) return@mapNotNull null
                val turns = capTurns(conversation.turns.takeLast(MAX_TURNS).mapNotNull { turn ->
                    if (turn.role != "user" && turn.role != "assistant") return@mapNotNull null
                    val text = turn.text.take(MAX_MESSAGE_CHARS)
                    if (text.isBlank()) return@mapNotNull null
                    turn.copy(
                        text = text,
                        citations = turn.citations.take(MAX_CITATIONS).map { it.take(MAX_CITATION_CHARS) },
                    )
                })
                if (turns.isEmpty()) return@mapNotNull null
                val firstQuestion = turns.firstOrNull { it.role == "user" }?.text.orEmpty()
                conversation.copy(
                    id = id,
                    title = conversation.title.trim().take(MAX_TITLE_CHARS)
                        .ifBlank { titleFor(firstQuestion) },
                    turns = turns,
                )
            }
            .sortedByDescending { it.updatedAtMillis }
            .toList()

        // 除了条数，还要限制总字符数：历史无限增长时写盘会被 MAX_FILE_CHARS 静默拒绝，
        // 表现为"最近聊的内容重启后没了"。至少保留最新一段。
        val conversations = mutableListOf<AiConversation>()
        var budget = MAX_TOTAL_CHARS
        for (candidate in candidates) {
            if (conversations.size >= MAX_CONVERSATIONS) break
            val size = candidate.turns.sumOf { it.text.length }
            if (conversations.isNotEmpty() && size > budget) break
            conversations += candidate
            budget -= size
        }
        val current = input.currentId?.takeIf { id -> conversations.any { it.id == id } }
        return AiChatState(conversations, current)
    }

    /** 单段对话也按字符裁剪，保留最近的问答（至少一条）。 */
    private fun capTurns(turns: List<ChatTurn>): List<ChatTurn> {
        var chars = 0
        val kept = ArrayDeque<ChatTurn>()
        for (turn in turns.asReversed()) {
            if (kept.isNotEmpty() && chars + turn.text.length > MAX_CONVERSATION_CHARS) break
            kept.addFirst(turn)
            chars += turn.text.length
        }
        return kept.toList()
    }

    private suspend fun persist(snapshot: AiChatState) {
        withContext(Dispatchers.IO) {
            runCatching {
                val text = AiChatCodec.encode(snapshot)
                if (text.length > MAX_FILE_CHARS) return@runCatching
                val atomic = AtomicFile(file)
                val out = atomic.startWrite()
                try {
                    out.write(text.toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(out)
                } catch (e: Exception) {
                    atomic.failWrite(out)
                    throw e
                }
            }
        }
    }

    private fun readFromDisk(): AiChatState? = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_CHARS) return@runCatching null
        val raw = AtomicFile(file).readFully().toString(Charsets.UTF_8)
        AiChatCodec.decode(raw)
    }.getOrNull()

    private fun newId(): String = "c_" + UUID.randomUUID().toString().replace("-", "").take(16)

    companion object {
        private const val FILE_NAME = "ai_chats.json"
        private const val MAX_CONVERSATIONS = 30
        private const val MAX_TURNS = 200
        private const val MAX_MESSAGE_CHARS = 20_000
        private const val MAX_CONVERSATION_CHARS = 500_000
        private const val MAX_TOTAL_CHARS = 2_000_000
        private const val MAX_CITATIONS = 20
        private const val MAX_CITATION_CHARS = 2_000
        private const val MAX_TITLE_CHARS = 60
        private const val MAX_FILE_CHARS = 4_000_000

        internal fun titleFor(question: String): String {
            val clean = question.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty()) return "新对话"
            return if (clean.length <= 24) clean else clean.take(24) + "…"
        }
    }
}

/**
 * 对话历史的 JSON 编码。沿用仓库自带的 [JsonValue]（`org.json` 在 JVM 单测里是 stub），
 * 因此编解码可以在普通 JUnit 里覆盖，不依赖 Android。
 */
internal object AiChatCodec {
    private const val VERSION = 1

    fun encode(state: AiChatState): String = JsonValue.Obj(
        mapOf(
            "version" to JsonValue.Num(VERSION.toString()),
            "currentId" to (state.currentId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
            "conversations" to JsonValue.Arr(
                state.conversations.map { conversation ->
                    JsonValue.Obj(
                        mapOf(
                            "id" to JsonValue.Str(conversation.id),
                            "title" to JsonValue.Str(conversation.title),
                            "createdAt" to JsonValue.Num(conversation.createdAtMillis.toString()),
                            "updatedAt" to JsonValue.Num(conversation.updatedAtMillis.toString()),
                            "turns" to JsonValue.Arr(
                                conversation.turns.map { turn ->
                                    JsonValue.Obj(
                                        mapOf(
                                            "role" to JsonValue.Str(turn.role),
                                            "text" to JsonValue.Str(turn.text),
                                            "failed" to JsonValue.Bool(turn.failed),
                                            "citations" to JsonValue.Arr(
                                                turn.citations.map { JsonValue.Str(it) },
                                            ),
                                        ),
                                    )
                                },
                            ),
                        ),
                    )
                },
            ),
        ),
    ).render()

    fun decode(raw: String): AiChatState {
        require(raw.length <= 4_000_000) { "对话记录过大" }
        val root = JsonParser(raw).parseDocument().asObject("对话记录")
        val version = (root.fields["version"] as? JsonValue.Num)?.raw?.toIntOrNull()
        require(version == VERSION) { "对话记录版本不支持" }
        val currentId = (root.fields["currentId"] as? JsonValue.Str)?.value
        val conversations = (root.fields["conversations"] as? JsonValue.Arr)?.items.orEmpty()
            .mapNotNull { value ->
                val obj = value as? JsonValue.Obj ?: return@mapNotNull null
                val id = (obj.fields["id"] as? JsonValue.Str)?.value
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val turns = (obj.fields["turns"] as? JsonValue.Arr)?.items.orEmpty()
                    .mapNotNull { item ->
                        val turn = item as? JsonValue.Obj ?: return@mapNotNull null
                        val role = (turn.fields["role"] as? JsonValue.Str)?.value ?: return@mapNotNull null
                        val text = (turn.fields["text"] as? JsonValue.Str)?.value ?: return@mapNotNull null
                        val failed = (turn.fields["failed"] as? JsonValue.Bool)?.value ?: false
                        val citations = (turn.fields["citations"] as? JsonValue.Arr)?.items.orEmpty()
                            .mapNotNull { (it as? JsonValue.Str)?.value }
                        ChatTurn(role, text, citations, failed)
                    }
                AiConversation(
                    id = id,
                    title = (obj.fields["title"] as? JsonValue.Str)?.value.orEmpty(),
                    createdAtMillis = (obj.fields["createdAt"] as? JsonValue.Num)?.raw?.toLongOrNull() ?: 0L,
                    updatedAtMillis = (obj.fields["updatedAt"] as? JsonValue.Num)?.raw?.toLongOrNull() ?: 0L,
                    turns = turns,
                )
            }
        return AiChatState(conversations, currentId)
    }
}
