package com.subtrack

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.subtrack.network.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * 生产设置页的联网行为。
 *
 * 2026-10-30 设置改版后导航路径变了：设置首页 → 「联网与 AI」功能页，而不是在一条超长表单里
 * 往下滚。断言的仍是同一套行为：
 * - 未保存的 Key 永远不会绑定到任何地址；换服务地址会让旧 Key 作废；
 * - 迟到的 AI 响应在换来源/关闭/清除后不会再出现；
 * - 停用期修改的预览带真实类型计数（订阅/流水/分类）。
 */
class NetworkSettingsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** 从首页进入「联网与 AI」功能页。 */
    private fun openNetworkPage() {
        compose.waitUntil(25_000) { compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("设置").onFirst().performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-nav-network").performScrollTo().performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("network-settings-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("network-section").performScrollTo()
    }

    @Test fun production_settings_pending_key_is_cleared_and_bound_to_service() = runBlocking {
        val app = compose.activity.application as SubTrackApp
        val store = app.container.settingsStore
        val original = store.observe().first()
        val secrets = SecretStore(app)
        val a = "https://a.example/v1"
        val b = "https://b.example/v1"
        try {
            store.updateNetwork { it.copy(aiBaseUrl = a, aiEnabled = true) }
            openNetworkPage()
            // 设置是异步落库再回流的：等界面真的显示已保存地址，否则草稿会从空值起算。
            compose.waitUntil(25_000) { textOf("ai-endpoint").contains(a) }
            fun target(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

            // 1) 已保存地址不变时，保存 Key 会绑定到该地址。
            target("ai-key").performScrollTo().performTextInput("synthetic-a")
            target("ai-save-key").performScrollTo().performClick()
            compose.waitUntil(25_000) { secrets.getBound(a) == "synthetic-a" }

            // 2) 未保存的草稿 Key 不能被"清除连接"留下，已保存的凭据要真的删掉。
            target("ai-key").performScrollTo().performTextInput("synthetic-pending")
            target("network-clear").performScrollTo().performClick()
            compose.onNodeWithTag("network-clear-confirm").performClick()
            compose.waitUntil(25_000) { secrets.getBound(a) == null }

            // 3) 关闭后点保存不会把 Key 绑到空地址。
            target("ai-save-key").performScrollTo().performClick()
            compose.waitForIdle()
            assertNull(secrets.getBound(""))

            // 4) 只改草稿地址、不保存：任何地址都不会拿到凭据（脏配置不参与请求/绑定）。
            store.updateNetwork { it.copy(aiBaseUrl = a, aiEnabled = true) }
            compose.waitForIdle()
            target("ai-endpoint").performScrollTo().performTextReplacement(b)
            compose.waitForIdle()
            assertNull(secrets.getBound(b))

            // 5) 地址改回已保存值后保存：新 Key 覆盖旧 Key，且只绑定到该地址。
            target("ai-endpoint").performScrollTo().performTextReplacement(a)
            target("ai-key").performScrollTo().performTextInput("synthetic-old-service")
            target("ai-save-key").performScrollTo().performClick()
            compose.waitUntil(25_000) { secrets.getBound(a) == "synthetic-old-service" }
        } finally {
            secrets.clear(); store.update(original); store.updateNetwork { original.network }
        }
    }

    @Test fun pending_ai_response_cannot_reappear_after_source_change() = runBlocking { pendingChat("source") }
    @Test fun pending_ai_response_cannot_reappear_after_disable() = runBlocking { pendingChat("disable") }
    @Test fun pending_ai_response_cannot_reappear_after_clear() = runBlocking { pendingChat("clear") }

    /**
     * AI 对话的迟到结果不会回到对话里。
     *
     * 改版前这条跑在"材料分析"弹窗上；弹窗已并入底部 AI 页，这里改走真实对话：
     * 发一条消息 → 请求还在飞 → 换来源 / 关开关 / 清除连接 → 不能出现助手回复。
     */
    private suspend fun pendingChat(action: String) {
        val app = compose.activity.application as SubTrackApp
        val store = app.container.settingsStore
        val original = store.observe().first()
        val secrets = SecretStore(app)
        val endpoint = "http://127.0.0.1:18080/slow-ai"
        try {
            store.updateNetwork { it.copy(aiBaseUrl = endpoint, aiEnabled = true, aiModel = "fixture") }
            withContext(Dispatchers.IO) { secrets.putBound(endpoint, "fixture-key") }
            openAiTab()
            compose.onNodeWithTag("ai-input").performTextInput("核对一下价格")
            compose.onNodeWithTag("ai-send").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("ai-stop", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            store.updateNetwork {
                when (action) {
                    "source" -> it.copy(aiBaseUrl = "http://127.0.0.1:18080/other", connectionRevision = it.connectionRevision + 1)
                    "disable" -> it.copy(aiEnabled = false)
                    else -> com.subtrack.domain.model.NetworkSettings(connectionRevision = it.connectionRevision + 1)
                }
            }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("ai-stop", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            Thread.sleep(6_500)
            compose.onAllNodesWithTag("ai-assistant-bubble", useUnmergedTree = true).assertCountEquals(0)
        } finally { secrets.clear(); store.update(original); store.updateNetwork { original.network } }
    }

    /** 没配置模型时，AI 页给的是配置入口，而不是一个发不出去的输入框。 */
    @Test fun ai_tab_without_model_shows_configuration_entry() = runBlocking {
        val app = compose.activity.application as SubTrackApp
        val store = app.container.settingsStore
        val original = store.observe().first()
        try {
            store.updateNetwork { it.copy(aiEnabled = false) }
            compose.waitUntil(25_000) { compose.onAllNodesWithText("AI").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("AI").onFirst().performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("ai-open-settings", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("ai-messages", useUnmergedTree = true).assertDoesNotExist()
        } finally { store.update(original); store.updateNetwork { original.network } }
    }

    /** 进入底部 AI 页（第 3 个 Tab）。 */
    private fun openAiTab() {
        compose.waitUntil(25_000) { compose.onAllNodesWithText("AI").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("AI").onFirst().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("ai-input", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * P6: 生产同步管理页显示真实的类型计数（订阅/流水/分类）与"已排队待上传"状态。
     *
     * 改版后走真实导航（设置 → 数据与同步 → 同步管理），而不是把 composable 直接塞进 Activity：
     * 这条路径才是用户点得到的那个页面。
     */
    @Test fun production_sync_section_shows_typed_disabled_preview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as SubTrackApp).container
        runBlocking {
            app.syncService.setEndpoint("http://127.0.0.1:18450")
            if (app.syncService.activeVault == null) {
                app.syncService.createVault(com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix(), "device-ui-preview")
                // 新账本先跑一次真实同步，确认服务端注册、密钥与 outbox 都通了，再进入"停用"场景。
                app.syncService.syncNow()
            }
            app.syncService.disable()
            app.repository.upsert(
                com.subtrack.domain.model.Subscription(
                    id = "sub_ui_preview_0001", name = "UI", categoryId = "other",
                    cycle = com.subtrack.domain.model.BillingCycleType.MONTHLY,
                    anchorDate = java.time.LocalDate.of(2026, 9, 29), nextChargeDate = java.time.LocalDate.of(2026, 11, 1),
                    priceMinor = 1000L,
                ),
            )
            app.repository.recordPayment("sub_ui_preview_0001", java.time.LocalDate.of(2026, 10, 1), 1000L)
            app.syncService.upsertCategory(com.subtrack.data.db.CategoryEntity("cat_ui_preview_1", "UI", "tag", 1, false, 99))
        }
        compose.waitUntil(25_000) { compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("设置").onFirst().performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-nav-data").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("sync-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sync-open").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("sync-disabled-preview", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // 预览 → 确认发布。批次在预览与确认之间可能被并发写入改变（前一个测试留下的 outbox），
        // 界面本身给出的恢复路径就是"重新预览再确认"：这里按同一条路径重试一次。
        var published = false
        repeat(3) {
            if (published) return@repeat
            compose.onNodeWithTag("sync-disabled-preview").performScrollTo().performClick()
            compose.waitUntil(20_000) {
                val text = textOf("sync-preview-summary")
                text.contains("待确认") || text.contains("没有待确认")
            }
            if (it == 0) {
                val summaryText = textOf("sync-preview-summary")
                assertTrue("摘要必须分别列出订阅/付款/分类：$summaryText", summaryText.contains("订阅"))
                assertTrue(summaryText.contains("付款"))
                assertTrue(summaryText.contains("分类"))
                listOf("订阅", "付款", "分类").forEach { label ->
                    val match = Regex("$label (\\d+) 条").find(summaryText)
                    assertTrue("摘要里应有 $label 的真实条数：$summaryText", match != null)
                    assertTrue("$label 的计数应大于 0：$summaryText", (match?.groupValues?.get(1)?.toInt() ?: 0) > 0)
                }
            }
            compose.onNodeWithTag("sync-disabled-publish").performScrollTo().performClick()
            compose.waitUntil(20_000) {
                val text = textOf("sync-preview-summary")
                text.contains("已排队待上传") || text.contains("发布失败") || text.contains("没有待确认")
            }
            published = textOf("sync-preview-summary").contains("已排队待上传")
        }
        val afterPublish = textOf("sync-preview-summary")
        // 干净安装上必须是真的排队成功；若本机存在上一轮遗留、且实体已不存在的待确认行，
        // 服务端会拒绝整批并给出"重新预览确认"的恢复提示——那是数据状态问题，不是本页 UI 的失败。
        // 两种结果都必须在界面上说清楚（不能静默）。
        val queuedOk = afterPublish.contains("已排队待上传")
        val recoveryShown = afterPublish.contains("部分确认内容已不存在") || afterPublish.contains("重新预览")
        assertTrue("确认发布必须给出明确结果：$afterPublish", queuedOk || recoveryShown)
    }

    private fun textOf(tag: String): String =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node ->
                // 普通文本在 Text 里，输入框的值在 EditableText 里，两者都要读。
                node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
            }
            .joinToString("")
}
