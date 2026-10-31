package com.subtrack

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 同步管理页的真实操作链（设置 → 数据与同步 → 同步管理）。
 *
 * 改版把同步从"一列 TextButton"改成"状态卡 + 分步设置 + 分区管理"，这个测试按用户的真实路径走：
 * 第 1 步填服务地址 → 第 2 步创账本（设备名 + 安装令牌）→ 第 3 步确认初始合并 →
 * 立即同步 → 刷新设备 → 停用 → 停用期修改预览 → 恢复同步。
 *
 * 如果本机已经绑定过账本（同一台设备上重跑），就跳过创建步骤，只验证已配置状态下的管理动作，
 * 这样测试既能在干净安装上跑完整流程，也能重复执行。
 *
 * 依赖与本机其它同步测试相同的本地服务（18080 fixture / 18450 sync server）。
 */
class SyncSettingsUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun texts(tag: String): String =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() }
            .joinToString("|")

    /** 整屏可见文本：失败时用来解释"为什么没走到下一步"。 */
    private fun screenText(): String =
        compose.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() }
            .joinToString(" | ")

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
    }

    /** 动作按钮在有请求在跑时是禁用的：等它重新可点再点，避免"点了没反应"。 */
    private fun tapWhenEnabled(tag: String) {
        compose.waitUntil(30_000) {
            val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes.first().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null
        }
        tap(tag)
    }

    private fun openSyncManagement() {
        compose.waitUntil(25_000) { compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("设置").onFirst().performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        tap("settings-nav-data")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("data-settings-list").fetchSemanticsNodes().isNotEmpty() }
        tap("sync-open")
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("sync-settings-list").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun vault_setup_sync_devices_and_disable_flow_through_the_settings_ui() {
        val app = compose.activity.application as SubTrackApp
        val service = app.container.syncService
        val alreadyBound = runBlocking { service.activeVault != null }
        openSyncManagement()

        if (!alreadyBound) {
            // 第 1 步：服务地址。
            compose.onNodeWithTag("sync-endpoint", useUnmergedTree = true).performScrollTo()
                .performTextReplacement("http://127.0.0.1:18450")
            tap("sync-save-endpoint")
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("sync-create", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // 第 2 步：设备名 + 安装令牌 → 创建账本。
            compose.onNodeWithTag("sync-device-name", useUnmergedTree = true).performScrollTo().performTextInput("ui-device")
            compose.onNodeWithTag("sync-setup-token", useUnmergedTree = true).performScrollTo()
                .performTextInput(SyncE2EDeviceTest.setupTokenForMatrix())
            tap("sync-create")
            val reachedStep3 = runCatching {
                compose.waitUntil(25_000) {
                    compose.onAllNodesWithTag("sync-publish-local", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
            }.isSuccess
            if (!reachedStep3) {
                throw AssertionError("创建账本后没有进入第 3 步。页面文本：${screenText()}")
            }
            // 恢复材料默认遮罩：不点"显示"就读不到原文。
            assertTrue("恢复材料默认必须遮罩", texts("sync-created-recovery").contains("•"))

            // 第 3 步：确认初始合并（唯一会把本机数据发布出去的按钮）。
            tap("sync-publish-local")
            compose.waitUntil(25_000) { runBlocking { service.status().active } }
        } else if (!runBlocking { service.status().active }) {
            // 已绑定但停用：先从管理页恢复，后续断言才有意义。
            tap("sync-reenable")
            compose.waitUntil(25_000) { runBlocking { service.status().active } }
        }

        // 已启用：状态卡出现"立即同步"，点一次并确认落库。
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("sync-now", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tapWhenEnabled("sync-now")
        compose.waitUntil(40_000) { runBlocking { runCatching { service.status().pendingUploads }.getOrDefault(1) == 0 } }
        assertTrue(
            "同步后应仍显示状态卡",
            compose.onAllNodesWithTag("sync-status-text", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )

        // 设备分区：刷新后设备列表来自真实服务。
        tapWhenEnabled("sync-status-refresh")
        compose.waitUntil(25_000) { runBlocking { runCatching { service.devices().isNotEmpty() }.getOrDefault(false) } }
        assertEquals(true, runBlocking { service.devices().first().name.isNotBlank() })

        // 停用：危险操作二次确认；停用后本机数据保留、可恢复。
        tapWhenEnabled("sync-disable")
        compose.onNodeWithTag("sync-disable-confirm").performClick()
        compose.waitUntil(25_000) { runBlocking { !service.status().active } }
        compose.waitUntil(25_000) {
            compose.onAllNodesWithTag("sync-reenable", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 停用期修改：预览摘要必须说明本批次内容。
        tapWhenEnabled("sync-disabled-preview")
        compose.waitUntil(25_000) {
            val summary = texts("sync-preview-summary")
            summary.contains("待确认") || summary.contains("没有待确认")
        }

        // 恢复同步：保留原 cursor/epoch。
        tap("sync-reenable")
        compose.waitUntil(30_000) { runBlocking { service.status().active } }
    }
}
