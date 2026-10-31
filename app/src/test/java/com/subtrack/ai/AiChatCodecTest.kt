package com.subtrack.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对话历史的编解码：损坏/过期数据只能降级，不能把整段历史变成崩溃。 */
class AiChatCodecTest {

    @Test fun round_trip_keeps_turns_citations_and_current_conversation() {
        val state = AiChatState(
            conversations = listOf(
                AiConversation(
                    id = "c_1",
                    title = "Kimi 会员价格",
                    createdAtMillis = 1_700_000_000_000L,
                    updatedAtMillis = 1_700_000_100_000L,
                    turns = listOf(
                        ChatTurn("user", "Kimi 现在订阅价格是多少"),
                        ChatTurn(
                            "assistant",
                            "Go 套餐 ¥49/月",
                            citations = listOf("https://www.kimi.com/help/membership/membership-pricing"),
                            failed = false,
                        ),
                        ChatTurn("assistant", "这次没查到", failed = true),
                    ),
                ),
            ),
            currentId = "c_1",
        )
        assertEquals(state, AiChatCodec.decode(AiChatCodec.encode(state)))
    }

    @Test fun unsupported_version_is_rejected_instead_of_guessing() {
        val encoded = AiChatCodec.encode(AiChatState())
            .replace("\"version\": 1", "\"version\": 99")
        assertTrue(runCatching { AiChatCodec.decode(encoded) }.isFailure)
    }

    @Test fun broken_entries_are_skipped_and_good_ones_survive() {
        val raw = """
            {"version":1,"currentId":null,"conversations":[
              {"id":"c_ok","title":"保留","createdAt":1,"updatedAt":2,
               "turns":[{"role":"user","text":"你好","failed":false,"citations":[]}]},
              {"title":"没有 id","createdAt":1,"updatedAt":2,"turns":[]}
            ]}
        """.trimIndent()
        val decoded = AiChatCodec.decode(raw)
        assertEquals(1, decoded.conversations.size)
        assertEquals("c_ok", decoded.conversations.first().id)
        assertNull(decoded.currentId)
    }

    @Test fun conversation_title_is_derived_from_the_first_question() {
        assertEquals("新对话", AiChatStore.titleFor("   "))
        assertEquals("Kimi 会员连续包月多少钱", AiChatStore.titleFor("Kimi   会员连续包月多少钱"))
        val long = AiChatStore.titleFor("一".repeat(40))
        assertTrue(long.endsWith("…"))
        assertTrue(long.length <= 25)
    }
}
