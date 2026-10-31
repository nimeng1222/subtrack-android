package com.subtrack

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.subtrack.network.NetworkTransport
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Run only in an isolated Debug build with separate TEST TLS and manifest trust. */
class UpdateUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun revoke_check_download_and_ready_install_from_production_settings() = runBlocking {
        val app = compose.activity.application as SubTrackApp
        val store = app.container.settingsStore
        val original = store.observe().first()
        val base = requireNotNull(InstrumentationRegistry.getArguments().getString("updateFixtureBase"))
        fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
        fun waitFor(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        suspend fun waitForRequest(path: String) {
            repeat(40) {
                val events = NetworkTransport(true).request("$base/test-events", throttle = false).toString(Charsets.UTF_8)
                if (events.contains("\"$path\"")) return
                delay(100)
            }
            error("fixture did not receive $path")
        }
        suspend fun configure(mode: String) {
            store.updateNetwork { it.copy(updateEnabled = true, updateManifestUrl = "$base/manifest/$mode", connectionRevision = it.connectionRevision + 1) }
            compose.waitForIdle()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("network-section"))
            node("update-check").performScrollTo().performClick()
        }
        fun assertRevoked() {
            Thread.sleep(6_500)
            compose.onAllNodesWithTag("update-download", useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithTag("update-install", useUnmergedTree = true).assertCountEquals(0)
            assertFalse(java.io.File(app.cacheDir, "updates/candidate.apk").exists())
        }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("设置").performClick()
            configure("slow")
            waitForRequest("/manifest/slow")
            node("network-clear").performScrollTo().performClick()
            assertRevoked()

            configure("slow-apk")
            waitFor("update-download"); node("update-download").performScrollTo().performClick()
            waitForRequest("/apk/slow")
            node("update-toggle").performScrollTo().performClick()
            assertRevoked()

            configure("valid")
            waitFor("update-download"); node("update-download").performScrollTo().performClick()
            waitFor("update-install")
            node("update-endpoint").performScrollTo().performTextReplacement("$base/manifest/slow")
            assertRevoked()

            configure("valid")
            waitFor("update-download"); node("update-download").performScrollTo().performClick()
            waitFor("update-install")
            compose.onNodeWithText("订阅").performClick()
            assertRevoked()
        } finally { store.update(original); store.updateNetwork { original.network } }
    }
}
