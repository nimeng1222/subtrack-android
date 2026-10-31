package com.subtrack

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.subtrack.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime

/**
 * 设置改版（2026-10-30）的信息架构回归。
 *
 * 锁住的是"入口页 + 功能页"这套结构本身，而不是某一行像素：
 * - 首页只有 8 个入口，没有任何常驻输入框、URL、Key、恢复包或同步操作按钮；
 * - 每个功能页都能进、都能用顶部返回 / 系统返回回到首页；
 * - 首页摘要来自真实设置（不是演示值），改完设置返回后立刻反映；
 * - 多字段配置的草稿不会因为"敲了字"就落库，取消即还原。
 */
class SettingsIaDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val entryTags = listOf(
        "settings-nav-reminder", "settings-nav-budget", "settings-nav-currency", "settings-nav-appearance",
        "settings-nav-data", "settings-nav-network", "settings-nav-security", "settings-nav-about",
    )

    private fun openSettings() {
        compose.waitUntil(25_000) { compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("设置").onFirst().performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun texts(tag: String): String =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() }
            .joinToString("|")

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun store() = (compose.activity.application as SubTrackApp).container.settingsStore

    private fun current() = runBlocking { store().observe().first() }

    @Test fun home_has_eight_entries_and_no_inline_configuration() {
        openSettings()
        entryTags.forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertExists()
        }
        // 首页不允许出现任何表单/危险操作：URL、Key、恢复包、同步动作都必须在功能页里。
        listOf("ai-key", "ai-endpoint", "sync-now", "network-clear", "fx-input", "budget-input").forEach { tag ->
            assertTrue("设置首页不应出现 $tag", compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        // 摘要必须来自真实设置。
        // 提醒时间可能被别的用例改过：只断言这一行确实显示了"每天 HH:mm"这种真实摘要。
        val reminderRow = texts("settings-nav-reminder")
        assertTrue("提醒行应显示真实提醒时刻，实际：$reminderRow", Regex("每天 \\d{2}:\\d{2}").containsMatchIn(reminderRow))
        assertTrue("关于行应显示真实版本", texts("settings-nav-about").contains(BuildConfig.VERSION_NAME))
        assertTrue("同步行应如实说明本机状态", texts("settings-nav-data").let { it.contains("本机") || it.contains("已连接") })
    }

    @Test fun every_function_page_back_navigation_returns_home() {
        val routes = listOf(
            "settings-nav-reminder" to "reminder-settings-list",
            "settings-nav-budget" to "budget-settings-list",
            "settings-nav-currency" to "currency-settings-list",
            "settings-nav-appearance" to "appearance-settings-list",
            "settings-nav-data" to "data-settings-list",
            "settings-nav-network" to "network-settings-list",
            "settings-nav-security" to "security-settings-list",
            "settings-nav-about" to "about-settings-list",
        )
        openSettings()
        routes.forEach { (entry, listTag) ->
            compose.onNodeWithTag(entry).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(listTag).fetchSemanticsNodes().isNotEmpty() }
            // 系统返回与顶部返回走同一条路由逻辑。
            back()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        }
        // 顶部返回按钮同样可用。
        compose.onNodeWithTag("settings-nav-about").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("about-settings-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun reminder_time_and_budget_round_trip_reflect_on_home() {
        val original = current()
        openSettings()
        try {
            compose.onNodeWithTag("settings-nav-reminder").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reminder-time-row").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminder-time-row").performScrollTo().performClick()
            compose.onNodeWithTag("time-hour-21").performScrollTo().performClick()
            compose.onNodeWithTag("time-minute-30").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("time-preview").assertTextContains("21:30", substring = true)
            compose.onNodeWithTag("time-save").performClick()
            compose.waitUntil(10_000) { current().reminderTime == LocalTime.of(21, 30) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("time-preview").fetchSemanticsNodes().isEmpty() }
            back()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
            val homeReminderRow = runCatching {
                compose.waitUntil(10_000) { texts("settings-nav-reminder").contains("21:30") }
            }.isSuccess
            assertTrue("首页提醒行应反映刚保存的 21:30，实际：${texts("settings-nav-reminder")}", homeReminderRow)

            // 预算：保存与清除都要落到设置库，首页摘要同步变化。
            compose.onNodeWithTag("settings-nav-budget").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("budget-row").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("budget-row").performScrollTo().performClick()
            compose.onNodeWithTag("budget-input").performScrollTo().performTextInput("300")
            compose.onNodeWithTag("budget-save").performClick()
            compose.waitUntil(10_000) { current().monthlyBudgetMinor == 30_000L }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("budget-input").fetchSemanticsNodes().isEmpty() }
            back()
            compose.waitUntil(10_000) { texts("settings-nav-budget").contains("300") }

            compose.onNodeWithTag("settings-nav-budget").performScrollTo().performClick()
            compose.onNodeWithTag("budget-row").performScrollTo().performClick()
            compose.onNodeWithTag("budget-clear").performClick()
            compose.waitUntil(10_000) { current().monthlyBudgetMinor == null }
        } finally {
            runBlocking { store().update(original) }
        }
    }

    @Test fun currency_manual_rate_and_appearance_are_authoritative_here() {
        val original = current()
        openSettings()
        try {
            compose.onNodeWithTag("settings-nav-currency").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("fx-row-USD").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fx-row-USD").performScrollTo().performClick()
            compose.onNodeWithTag("fx-input").performScrollTo().performTextInput("7.2")
            compose.onNodeWithTag("fx-save").performClick()
            compose.waitUntil(10_000) { current().fxRates["USD"] == 720L }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("fx-input").fetchSemanticsNodes().isEmpty() }
            back()
            val backOk = runCatching {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            assertTrue(
                "从币种页返回首页失败；当前 fx=${current().fxRates} 页面标签=${compose.onAllNodesWithTag("currency-settings-list").fetchSemanticsNodes().isNotEmpty()}",
                backOk,
            )

            compose.onNodeWithTag("settings-nav-appearance").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("appearance-settings-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("深色").onFirst().performClick()
            compose.waitUntil(10_000) { current().themeMode == ThemeMode.DARK }
            back()
            compose.waitUntil(10_000) { texts("settings-nav-appearance").contains("深色") }
        } finally {
            runBlocking { store().update(original) }
        }
    }

    @Test fun unsaved_ai_draft_never_reaches_settings_store() {
        val original = current()
        openSettings()
        try {
            compose.onNodeWithTag("settings-nav-network").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("ai-endpoint").fetchSemanticsNodes().isNotEmpty() }
            val before = current().network.aiBaseUrl
            compose.onNodeWithTag("ai-endpoint").performScrollTo().performTextInput("https://draft.example/v1")
            compose.waitForIdle()
            assertEquals("未保存的草稿不能写进设置", before, current().network.aiBaseUrl)
            val cancel = compose.onAllNodesWithTag("ai-draft-cancel", useUnmergedTree = true).fetchSemanticsNodes()
            if (cancel.isNotEmpty()) compose.onNodeWithTag("ai-draft-cancel").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(before, current().network.aiBaseUrl)
        } finally {
            runBlocking { store().update(original) }
        }
    }

    @Test fun sync_management_is_reachable_in_two_taps_and_shows_real_state() {
        openSettings()
        compose.onNodeWithTag("settings-nav-data").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("sync-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("sync-open").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("sync-settings-list").fetchSemanticsNodes().isNotEmpty() }
        // 真实状态：状态是异步读出来的，先等状态卡落定（未配置/已停用/已连接三种都会出现）。
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("sync-state-card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val step = compose.onAllNodesWithTag("sync-step", useUnmergedTree = true).fetchSemanticsNodes()
        val hasSyncButton = compose.onAllNodesWithTag("sync-now", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val hasReenable = compose.onAllNodesWithTag("sync-reenable", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val service = (compose.activity.application as SubTrackApp).container.syncService
        val bound = runBlocking { service.activeVault != null }
        val running = bound && runBlocking { service.status().active }
        when {
            running -> assertTrue("已启用同步时必须能看到立即同步", hasSyncButton)
            bound -> {
                assertTrue("已停用但绑定了账本时必须给恢复入口", hasReenable)
                assertTrue("停用状态不应出现立即同步", !hasSyncButton)
            }
            else -> {
                assertTrue("未配置时必须显示分步设置", step.isNotEmpty())
                assertTrue("未配置时不应出现立即同步", !hasSyncButton)
            }
        }
        back()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("data-settings-list").fetchSemanticsNodes().isNotEmpty() }
        back()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
    }

    /** 从功能页返回时必须回到原来的滚动位置，而不是跳回顶部。 */
    @Test fun returning_from_a_function_page_restores_home_scroll_position() {
        openSettings()
        compose.onNodeWithText("常用设置").assertIsDisplayed()
        compose.onNodeWithTag("settings-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        // 411dp 下首页有可能一屏放得下；只有确实滚动了才断言"位置被保留"。
        val scrolled = compose.onAllNodesWithText("常用设置").fetchSemanticsNodes().isEmpty() ||
            runCatching { compose.onNodeWithText("常用设置").assertIsNotDisplayed() }.isSuccess

        compose.onNodeWithTag("settings-nav-about").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("about-settings-list").fetchSemanticsNodes().isNotEmpty() }
        back()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // 无论是否可滚，返回后都应停在首页且行仍可点。
        compose.onNodeWithTag("settings-nav-about").assertExists()
        compose.onNodeWithTag("settings-nav-reminder").assertExists()
        if (scrolled) {
            // 顶部标题仍然不可见 = 回到了原来的滚动位置，而不是跳回顶部。
            compose.onNodeWithText("常用设置").assertIsNotDisplayed()
        } else {
            compose.onNodeWithText("常用设置").assertIsDisplayed()
        }
    }

    @Test fun rotation_keeps_the_current_settings_page() {
        openSettings()
        compose.onNodeWithTag("settings-nav-currency").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("currency-settings-list").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("currency-settings-list").fetchSemanticsNodes().isNotEmpty() }
        // 回到首页后仍是首页，不会因为重建落到错误的层级。
        back()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(compose.onAllNodesWithTag("settings-nav-reminder").fetchSemanticsNodes().firstOrNull())
    }

    /**
     * 用户报告："AI 页点『去设置里配置』没反应"。
     *
     * 原因是路由改了、底部 Tab 没跟着切，界面还停在 AI 页。这个用例从真实底部 Tab 进入，
     * 断言点击后确实落在「联网与 AI」功能页，而不是只改了一个看不见的状态。
     */
    @Test fun ai_page_config_button_opens_network_settings() {
        runBlocking {
            store().updateNetwork { it.copy(aiEnabled = false, aiBaseUrl = "") }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("AI").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("AI").onFirst().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("ai-open-settings").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("ai-open-settings").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("network-settings-list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("toggle-ai").assertExists()
    }
}
