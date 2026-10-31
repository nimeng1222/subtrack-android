package com.subtrack

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.subtrack.sync.ConflictView
import com.subtrack.sync.SyncStatusSnapshot
import com.subtrack.ui.network.SyncSettingsPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** N4 sync settings surface: status/pending/conflicts/last-revision shown; sync-now/rotate/key-role actions route; null state. */
class SyncSettingsPanelDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun snapshot() = SyncStatusSnapshot("vault_t", "device_t", 1, cursor = 5, pendingUploads = 3, conflicts = 1)
    private fun view(opId: String) = ConflictView(opId, "subscription", "e-$opId", "Netflix / 家庭版")

    @Test fun status_actions_and_conflict_panel_route() {
        var synced = 0; var rotated = 0; val remote = mutableListOf<String>()
        compose.setContent {
            SyncSettingsPanel(
                status = snapshot(),
                conflicts = listOf(view("operation_x1")),
                reminderRoleLabel = "本机为提醒设备",
                onSyncNow = { synced++ },
                onRotateKey = { rotated++ },
                onKeepLocal = {},
                onKeepRemote = { remote += it },
            )
        }
        compose.onNodeWithTag("sync-status-text").assertTextContains("待上传：3", substring = true)
        compose.onNodeWithTag("sync-status-text").assertTextContains("冲突：1", substring = true)
        compose.onNodeWithTag("sync-role").assertTextContains("本机为提醒设备", substring = true)
        compose.onNodeWithTag("sync-now").performClick()
        compose.onNodeWithTag("sync-rotate").performClick()
        compose.onNodeWithTag("sync-keep-remote-operation_x1").performClick()
        compose.waitForIdle()
        assertEquals(1, synced); assertEquals(1, rotated)
        assertEquals(listOf("operation_x1"), remote)
    }

    @Test fun disabled_state_when_no_vault() {
        compose.setContent {
            SyncSettingsPanel(null, emptyList(), "本机提醒（未指定专门设备）", {}, {}, {}, {})
        }
        compose.onNodeWithTag("sync-disabled").assertTextContains("同步未启用", substring = true)
        assertTrue(compose.onAllNodesWithTag("sync-now").fetchSemanticsNodes().isEmpty())
    }
}
