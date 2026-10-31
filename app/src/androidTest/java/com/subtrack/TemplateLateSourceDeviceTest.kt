package com.subtrack

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.subtrack.domain.model.NetworkSettings
import com.subtrack.network.NetworkTransport
import com.subtrack.network.OnlineRepository
import com.subtrack.ui.network.TemplatePicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * N1 remaining matrix (device): (1) a slow refresh from source A that is still in flight when the user switches to
 * source B must never publish A's late success to the UI; (2) a cached catalog is readable after a simulated process
 * restart with no further network call (offline restart).
 */
class TemplateLateSourceDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val base = "http://127.0.0.1:18080"

    @Test fun late_refresh_from_previous_source_never_publishes_after_switch() {
        val slowA = "$base/templates-slow-${UUID.randomUUID()}"
        val b = "https://b.example/v1"
        val cfg = mutableStateOf(NetworkSettings(templatesEnabled = true, templatesBaseUrl = slowA, connectionRevision = 1))
        compose.setContent { TemplatePicker(cfg.value) {} }
        compose.waitForIdle()
        compose.onNodeWithTag("tpl-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tpl-refresh").performClick()      // slow (5s) fetch from A starts
        compose.waitForIdle()
        compose.runOnUiThread {                                 // switch source while A is in flight
            cfg.value = NetworkSettings(templatesEnabled = true, templatesBaseUrl = b, connectionRevision = 2)
        }
        compose.waitForIdle()
        Thread.sleep(6_000)                                     // let A's late response land after dispose
        compose.onAllNodesWithText("在线数据版本", substring = true).assertCountEquals(0)
    }

    @Test fun cached_catalog_is_readable_after_restart_without_network() = runBlocking {
        val context = Round2Instrumentation.instance.targetContext
        val url = "$base/templates"
        OnlineRepository(context, NetworkTransport(true)).templates(url)    // real fetch + cache
        // Simulated process restart: a brand-new repository reads the cache with no further network call.
        val restored = OnlineRepository(context, NetworkTransport(true)).cachedCatalog(url)
        assertNotNull(restored)
        assertEquals(4, restored!!.templates.size)
    }
}
