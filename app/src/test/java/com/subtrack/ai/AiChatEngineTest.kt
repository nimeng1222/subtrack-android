package com.subtrack.ai

import com.subtrack.data.backup.JsonValue
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.network.parseObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * AI 对话引擎的协议层：请求体形状、工具调用解析、HTML 压缩、台账摘要。
 *
 * 这几处出错不会抛异常，只会让"AI 查了网页却给了假价格"——所以逐项锁住。
 */
class AiChatEngineTest {

    private fun body(model: String = "test-model"): String =
        AiChatEngine.requestBody(
            model,
            AiChatEngine.initialMessages("SYS", listOf(ChatTurn("user", "你好"), ChatTurn("assistant", "在"))).also {
                it += AiChatEngine.userMessage("Netflix 多少钱")
            },
        )

    @Test fun request_body_carries_model_tools_and_full_history() {
        val root = parseObject(body())
        assertEquals("test-model", root.fields["model"]?.let { (it as JsonValue.Str).value })
        val messages = (root.fields["messages"] as JsonValue.Arr).items
        assertEquals(4, messages.size) // system + 2 历史 + 当前问题
        val tools = (root.fields["tools"] as JsonValue.Arr).items
        val names = tools.map { tool ->
            val fn = ((tool as JsonValue.Obj).fields["function"] as JsonValue.Obj)
            (fn.fields["name"] as JsonValue.Str).value
        }
        assertEquals(listOf("fetch_page", "read_ledger"), names)
        // 抓取工具必须有必填 url
        val fetchParams = ((((tools[0] as JsonValue.Obj).fields["function"] as JsonValue.Obj)
            .fields["parameters"] as JsonValue.Obj))
        val required = (fetchParams.fields["required"] as JsonValue.Arr).items.map { (it as JsonValue.Str).value }
        assertEquals(listOf("url"), required)
    }

    @Test fun tool_calls_are_parsed_with_arguments() {
        val response = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"fetch_page","arguments":"{\"url\":\"https://example.com/price\"}"}}
            ]}}]}
        """.trimIndent()
        val step = AiChatEngine.parseStep(response, emptyList())
        assertTrue(step is AiChatEngine.Step.Tools)
        val tools = step as AiChatEngine.Step.Tools
        assertEquals(1, tools.calls.size)
        assertEquals("fetch_page", tools.calls[0].name)
        assertEquals("call_1", tools.calls[0].id)
        assertEquals("https://example.com/price", parseObject(tools.calls[0].arguments).fields["url"]?.let { (it as JsonValue.Str).value })
    }

    @Test fun plain_answer_keeps_only_pages_that_were_really_opened() {
        val response = """{"choices":[{"message":{"role":"assistant","content":"查到了：每月 15.99 美元"}}]}"""
        val step = AiChatEngine.parseStep(response, listOf("https://official.example/plans", "https://official.example/plans"))
        assertTrue(step is AiChatEngine.Step.Answer)
        val answer = (step as AiChatEngine.Step.Answer)
        assertEquals("查到了：每月 15.99 美元", answer.text)
        assertEquals(listOf("https://official.example/plans"), answer.citations)
    }

    @Test fun empty_response_is_an_error_not_an_empty_bubble() {
        val response = """{"choices":[{"message":{"role":"assistant","content":""}}]}"""
        val error = runCatching { AiChatEngine.parseStep(response, emptyList()) }.exceptionOrNull()
        assertTrue("空回答必须报错", error != null)
    }

    @Test fun html_becomes_readable_text_without_scripts() {
        val html = """
            <html><head><style>.x{color:red}</style><script>alert('hi')</script></head>
            <body><h1>Pricing</h1><p>Standard&nbsp;plan</p><p>US$&nbsp;15.49 / month</p>
            <script>steal()</script></body></html>
        """.trimIndent()
        val text = HtmlText.toReadable(html)
        assertTrue(text.contains("Pricing"))
        assertTrue(text.contains("15.49"))
        assertFalse("脚本内容不能进正文", text.contains("alert"))
        assertFalse(text.contains("color:red"))
    }

    @Test fun ledger_digest_has_the_facts_but_not_private_notes() {
        val today = LocalDate.of(2026, 10, 31)
        val sub = Subscription(
            id = "sub_1",
            name = "Netflix",
            categoryId = "video",
            cycle = BillingCycleType.MONTHLY,
            anchorDate = today.minusMonths(6),
            nextChargeDate = today.plusDays(3),
            priceMinor = 9_900L,
            currency = "USD",
            status = SubscriptionStatus.ACTIVE,
            note = "家庭共享；密码在家人群里，不要外传",
        )
        val digest = LedgerDigest.build(listOf(sub), today, recentPaidMinor = 12_345L, monthPaidMinor = 6_789L)
        assertTrue(digest.contains("Netflix"))
        assertTrue(digest.contains("USD"))
        assertTrue(digest.contains("每月"))
        assertTrue(digest.contains("3 天后"))
        assertTrue(digest.contains("近 90 天实付合计"))
        assertFalse("备注不能发给模型", digest.contains("密码"))
        assertFalse("内部 ID 不能发给模型", digest.contains("sub_1"))
    }

    @Test fun empty_ledger_is_stated_explicitly() {
        val digest = LedgerDigest.build(emptyList(), LocalDate.of(2026, 10, 31), null, null)
        assertTrue(digest.contains("暂无订阅"))
    }

    @Test fun chat_body_is_explicitly_non_streaming_and_can_drop_tools() {
        val withTools = parseObject(body())
        assertFalse("必须显式 stream=false，避免网关按流式返回", (withTools.fields["stream"] as JsonValue.Bool).value)
        assertTrue(withTools.fields.containsKey("tools"))

        val noTools = parseObject(
            AiChatEngine.requestBody("m", listOf(AiChatEngine.userMessage("hi")), includeTools = false),
        )
        assertFalse("不支持工具调用时不能带 tools", noTools.fields.containsKey("tools"))
        assertFalse(noTools.fields.containsKey("tool_choice"))
        assertEquals(1, (noTools.fields["messages"] as JsonValue.Arr).items.size)
    }

    @Test fun system_prompt_without_tools_stops_pretending_to_browse() {
        val tools = AiChatEngine.systemPrompt("2026-10-31", tools = true)
        assertTrue(tools.contains("fetch_page"))
        val plain = AiChatEngine.systemPrompt("2026-10-31", tools = false)
        assertFalse("没有工具时不能仍教模型去抓网页", plain.contains("fetch_page"))
        assertTrue(plain.contains("看不到本机台账"))
    }

    @Test fun ping_body_is_a_minimal_valid_request() {
        val root = parseObject(AiChatEngine.pingBody("my-model"))
        assertEquals("my-model", (root.fields["model"] as JsonValue.Str).value)
        assertEquals("16", (root.fields["max_tokens"] as JsonValue.Num).raw)
        assertEquals(1, (root.fields["messages"] as JsonValue.Arr).items.size)
        assertFalse(root.fields.containsKey("tools"))
    }

    @Test fun endpoint_that_stops_at_v1_is_completed_for_chat() {
        assertEquals("https://api.x.com/v1/chat/completions", com.subtrack.ai.AiEndpoint.chatUrl("https://api.x.com/v1"))
        assertEquals("https://api.x.com/v1/chat/completions", com.subtrack.ai.AiEndpoint.chatUrl("https://api.x.com/v1/"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            com.subtrack.ai.AiEndpoint.chatUrl("https://generativelanguage.googleapis.com/v1beta/openai"),
        )
        assertEquals("https://api.x.com/openai/chat/completions", com.subtrack.ai.AiEndpoint.chatUrl("https://api.x.com/openai"))
    }

    @Test fun a_full_endpoint_is_left_alone() {
        val full = "https://api.x.com/v1/chat/completions"
        assertEquals(full, com.subtrack.ai.AiEndpoint.chatUrl(full))
        assertFalse(com.subtrack.ai.AiEndpoint.autoCompleted(full))
        assertTrue(com.subtrack.ai.AiEndpoint.autoCompleted("https://api.x.com/v1"))
    }

    @Test fun error_body_is_turned_into_one_readable_line() {
        assertEquals(
            "Incorrect API key provided",
            com.subtrack.network.NetworkErrorText.summarize("""{"error":{"message":"Incorrect API key provided"}}"""),
        )
        assertEquals(
            "Forbidden",
            com.subtrack.network.NetworkErrorText.summarize("<html><head><style>x{}</style></head><body><h1>Forbidden</h1></body></html>"),
        )
        assertEquals("", com.subtrack.network.NetworkErrorText.summarize("   "))
        assertEquals("空对象不算说明", "", com.subtrack.network.NetworkErrorText.summarize("{}"))
        val long = com.subtrack.network.NetworkErrorText.summarize("a".repeat(1000))
        assertTrue(long.length <= 241 && long.endsWith("…"))
    }

    @Test fun only_tool_rejections_fall_back_to_plain_chat() {
        assertTrue(com.subtrack.ai.AiChatFallback.shouldRetryWithoutTools(403, "Forbidden"))
        assertTrue(com.subtrack.ai.AiChatFallback.shouldRetryWithoutTools(400, "tools is not supported"))
        assertTrue(com.subtrack.ai.AiChatFallback.shouldRetryWithoutTools(400, "Function calling unavailable"))
        assertFalse("401 是密钥问题，降级没用", com.subtrack.ai.AiChatFallback.shouldRetryWithoutTools(401, "bad key"))
        assertFalse(com.subtrack.ai.AiChatFallback.shouldRetryWithoutTools(400, "invalid model"))
        assertFalse(com.subtrack.ai.AiChatFallback.shouldRetryWithoutTools(429, "rate limited"))
    }
}
