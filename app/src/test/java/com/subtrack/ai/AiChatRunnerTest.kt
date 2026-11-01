package com.subtrack.ai

import com.subtrack.data.backup.JsonValue
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.NetworkSettings
import com.subtrack.network.NetworkTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.LocalDate

/**
 * 1.9.8 两个回归：
 * 1. 生成任务挂在应用级作用域上——切走 AI 页（页面 scope 被取消）不会打断生成；
 * 2. 超时的处理——读超时给出可辨认的失败、连接超时自动重试一次、总超时不再写成"已停止"；
 *    以及台账类问题只带 read_ledger，不会为了"我花了多少钱"去联网搜索。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiChatRunnerTest {

    private val settings = AppSettings(
        network = NetworkSettings(
            aiEnabled = true,
            aiBaseUrl = "https://api.example.com/v1",
            aiModel = "test-model",
        ),
    )

    private fun newRunner(
        scope: CoroutineScope,
        sink: AiChatSink,
        model: AiModelTransport,
        now: () -> Long = { 0L },
    ) = AiChatRunner(
        scope = scope,
        chatStore = sink,
        transport = NetworkTransport(debug = true),
        modelTransport = model,
        settingsProvider = { settings },
        keyProvider = { "sk-test" },
        todayProvider = { LocalDate.of(2026, 11, 1) },
        digestProvider = { "近 90 天实付合计：¥0.00" },
        nowMillis = now,
    )

    private fun answer(text: String): String =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""

    // ────────────── 切页不打断 ──────────────

    @Test
    fun `切走 AI 页取消页面 scope 后生成仍继续并写入回答`() = runTest {
        val sink = FakeSink()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<String>()
        val runner = newRunner(appScope, sink, model = { _, _, _ -> gate.await() })
        // 模拟 AI 页自己的 rememberCoroutineScope()
        val screenJob = launch(StandardTestDispatcher(testScheduler)) { awaitCancellation() }

        runner.send("我花了多少钱了")
        advanceUntilIdle()
        assertTrue("发送后应处于生成中", runner.state.value.running)

        // 用户切到「订阅/统计」：页面离开组合，页面 scope 被取消
        screenJob.cancel()
        advanceUntilIdle()
        assertTrue("切页不能取消应用级生成任务", runner.state.value.running)
        assertTrue("切页时还没回答，不应写入任何结果", sink.appended.isEmpty())

        gate.complete(answer("你近 90 天实付合计 ¥0.00。"))
        advanceUntilIdle()
        assertFalse(runner.state.value.running)
        assertNull(runner.state.value.error)
        val turn = sink.appended.single()
        assertEquals("assistant", turn.role)
        assertFalse("正常完成的回答不能标成失败", turn.failed)
        assertTrue(turn.text.contains("¥0.00"))
    }

    @Test
    fun `用户主动停止仍然有效且写入已停止`() = runTest {
        val sink = FakeSink()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = newRunner(appScope, sink, model = { _, _, _ -> awaitCancellation() })
        runner.send("我花了多少钱了")
        advanceUntilIdle()
        assertTrue(runner.state.value.running)

        runner.stop()
        advanceUntilIdle()
        assertFalse(runner.state.value.running)
        assertTrue(sink.appended.single().text.startsWith("已停止"))
        assertTrue(sink.appended.single().failed)
    }

    // ────────────── 超时行为 ──────────────

    @Test
    fun `读超时不重试且给出可辨认的超时失败而不是已停止`() = runTest {
        val sink = FakeSink()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var calls = 0
        var now = 0L
        val runner = newRunner(
            appScope, sink,
            model = { _, _, _ ->
                calls++
                now = 30_000 // 已经等了 30 秒才失败 → 读超时，重试只会让用户多等
                throw SocketTimeoutException("timeout")
            },
            now = { now },
        )
        runner.send("我花了多少钱了")
        advanceUntilIdle()

        assertEquals("读超时不应自动重试", 1, calls)
        assertFalse(runner.state.value.running)
        assertTrue(
            "错误条要说清是超时：${runner.state.value.error}",
            runner.state.value.error.orEmpty().contains("超时"),
        )
        val turn = sink.appended.single()
        assertTrue("失败原因要写进对话：${turn.text}", turn.text.startsWith("这次没有完成："))
        assertTrue(turn.text.contains("timeout") || turn.text.contains("超时"))
        assertFalse("超时不能被写成用户主动停止", turn.text.startsWith("已停止"))
    }

    @Test
    fun `连接阶段快速失败自动重试一次并成功`() = runTest {
        val sink = FakeSink()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var calls = 0
        val runner = newRunner(appScope, sink, model = { _, _, _ ->
            calls++
            if (calls == 1) throw SocketTimeoutException("timeout")
            answer("你近 90 天实付合计 ¥0.00。")
        })
        runner.send("我花了多少钱了")
        advanceUntilIdle()

        assertEquals("10 秒内失败应重试一次", 2, calls)
        assertNull(runner.state.value.error)
        assertTrue(sink.appended.single().text.contains("¥0.00"))
    }

    @Test
    fun `总超时按超时报告而不是已停止`() = runTest {
        val sink = FakeSink()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = newRunner(appScope, sink, model = { _, _, _ ->
            // 模拟 NetworkTransport 的 withTimeout(MODEL_TOTAL_TIMEOUT_MS)
            withTimeout(1_000) { awaitCancellation() }
        })
        runner.send("我花了多少钱了")
        advanceUntilIdle()

        assertFalse(runner.state.value.running)
        val turn = sink.appended.single()
        assertTrue("总超时要写成超时：${turn.text}", turn.text.contains("响应超时"))
        assertFalse("总超时不能写成已停止", turn.text.startsWith("已停止"))
    }

    // ────────────── 统计类问题不走联网搜索 ──────────────

    @Test
    fun `台账类问题只带 read_ledger 不带联网工具`() {
        assertTrue(AiChatEngine.looksLikeLedgerQuestion("我花了多少钱了"))
        assertTrue(AiChatEngine.looksLikeLedgerQuestion("这个月我花了多少"))
        assertFalse(
            "问服务价格是研究外部事实，不能被当成台账问题",
            AiChatEngine.looksLikeLedgerQuestion("Kimi 会员现在多少钱"),
        )

        val body = AiChatEngine.requestBody(
            "test-model",
            listOf(AiChatEngine.userMessage("我花了多少钱了")),
            includeTools = true,
            ledgerOnly = true,
        )
        assertTrue(body.contains("read_ledger"))
        assertFalse("统计查询不应再触发联网搜索", body.contains("search_web"))
        assertFalse(body.contains("fetch_page"))
    }

    @Test
    fun `台账类问题实际发给模型的第一轮请求不含联网工具`() = runTest {
        val sink = FakeSink()
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val bodies = mutableListOf<String>()
        val runner = newRunner(appScope, sink, model = { _, body, _ ->
            bodies += body
            answer("你近 90 天实付合计 ¥0.00。")
        })
        runner.send("我花了多少钱了")
        advanceUntilIdle()

        assertEquals(1, bodies.size)
        // 不能直接对 body 做字符串匹配：system prompt 的规则文本里本来就会提到 search_web。
        val tools = com.subtrack.network.parseObject(bodies.single()).fields["tools"] as? JsonValue.Arr
        val names = tools?.items.orEmpty().mapNotNull { item ->
            val fn = (item as? JsonValue.Obj)?.fields?.get("function") as? JsonValue.Obj
            (fn?.fields?.get("name") as? JsonValue.Str)?.value
        }
        assertEquals("台账问题只暴露 read_ledger", listOf("read_ledger"), names)
    }

    private class FakeSink : AiChatSink {
        private val _state = MutableStateFlow(AiChatState())
        override val state = _state.asStateFlow()
        val appended = mutableListOf<ChatTurn>()

        override suspend fun ensureLoaded() = Unit

        override suspend fun beginTurn(question: String): String {
            val conversation = AiConversation(
                id = "c1",
                title = question,
                createdAtMillis = 0,
                updatedAtMillis = 0,
                turns = listOf(ChatTurn("user", question)),
            )
            _state.value = AiChatState(listOf(conversation), "c1")
            return "c1"
        }

        override suspend fun appendTurn(conversationId: String, turn: ChatTurn) {
            appended += turn
            _state.value = AiChatState(
                conversations = _state.value.conversations.map {
                    if (it.id == conversationId) it.copy(turns = it.turns + turn) else it
                },
                currentId = _state.value.currentId,
            )
        }
    }
}
