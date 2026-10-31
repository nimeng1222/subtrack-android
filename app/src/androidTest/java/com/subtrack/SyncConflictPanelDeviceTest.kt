package com.subtrack

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.subtrack.sync.ConflictView
import com.subtrack.ui.network.SyncConflictPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * N4 conflict-resolution UI component: shows each conflict with an explainable label and two explicit choices; clicking
 * them routes the operation id to the right resolver (keep-local vs keep-remote); empty state is explicit.
 */
class SyncConflictPanelDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun view(opId: String, label: String) = ConflictView(opId, "subscription", "e-$opId", label)

    @Test fun renders_conflicts_and_routes_both_resolutions() {
        val local = mutableListOf<String>()
        val remote = mutableListOf<String>()
        compose.setContent {
            SyncConflictPanel(
                conflicts = listOf(view("operation_c1", "Netflix / 家庭版"), view("operation_c2", "Spotify / 学生")),
                onKeepLocal = { local += it },
                onKeepRemote = { remote += it },
            )
        }
        compose.onNodeWithTag("sync-conflict-panel").assertExists()
        compose.onNodeWithTag("sync-conflict-operation_c1").assertTextEquals("Netflix / 家庭版")
        compose.onNodeWithTag("sync-keep-local-operation_c1").performClick()
        compose.onNodeWithTag("sync-keep-remote-operation_c2").performClick()
        compose.waitForIdle()
        assertEquals(listOf("operation_c1"), local)
        assertEquals(listOf("operation_c2"), remote)
    }

    @Test fun empty_state_is_explicit() {
        compose.setContent { SyncConflictPanel(emptyList(), onKeepLocal = {}, onKeepRemote = {}) }
        compose.onNodeWithTag("sync-conflict-empty").assertTextContains("无冲突")
    }
}
