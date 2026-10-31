package com.subtrack

import androidx.test.platform.app.InstrumentationRegistry
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.SyncProductService
import com.subtrack.sync.SwitchableSyncWriteGateway
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * F8 product-path two-device matrix. Every phase runs through the SAME production entry points the UI calls
 * ([SyncProductService]: setEndpoint / createVault / joinVault / syncNow / status / conflictsRepository / devices / revoke)
 * against the real Python sync server. The recovery material + pairing credential travel out-of-band (the host copies the
 * provisioning file A→B exactly like a user transferring them), never through a host-written database.
 *
 * ISOLATED TEST ONLY: selected with `-e syncProductPhase`, not part of the strict 75-test default inventory.
 */
class SyncProductFlowDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    /**
     * The custom monitoring runner may start the test before the target Application's onCreate finishes; wait briefly for
     * the production container instead of reading an uninitialised lateinit field.
     */
    private suspend fun awaitContainer(): com.subtrack.AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("app container was not initialised")
    }
    private val subId = "sub_product_matrix_0001"
    private val stateFile get() = File(context.filesDir, "sync-product-state.json")
    private val peerFile get() = File(context.filesDir, "sync-product-state-from-peer.json")
    private val endpoint = "http://127.0.0.1:18450"
    private val setupToken = "subtrack-e2e-test-setup-token-0123456789"

    private fun phase() = requireNotNull(InstrumentationRegistry.getArguments().getString("syncProductPhase"))

    private fun sub(name: String) = Subscription(
        id = subId, name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.of(2026, 9, 29), nextChargeDate = LocalDate.of(2026, 11, 1), priceMinor = 1000L,
    )

    @Test fun run_phase(): Unit = runBlocking {
        val container = awaitContainer()
        val service: SyncProductService = container.syncService
        when (phase()) {
            "a_init" -> {
                service.setEndpoint(endpoint)
                dbg().delete()
                val (vault, recovery) = service.createVault(setupToken, "device-a")
                val pairing = service.pairingCredential()
                stateFile.writeText(JSONObject().put("vault", vault).put("recovery", recovery).put("pairing", pairing).toString())
                // Initial merge preview must see the local ledger BEFORE anything is uploaded.
                container.repository.upsert(sub("A-INIT"))
                val preview = service.initialMergePreview()
                assertTrue("preview must count existing local subscriptions", (preview["subscriptions"] ?: 0) >= 1)
                val published = service.publishInitialSnapshot()
                assertTrue("initial merge must enqueue local rows", (published["subscriptions"] ?: 0) >= 1)
                assertTrue("initial merge uploads", service.syncNow().uploaded >= 1)
                assertEquals(0, service.status().pendingUploads)
                dbg().writeText("a_init ok vault=" + vault)
            }
            "b_join" -> {
                service.setEndpoint(endpoint)
                val peer = JSONObject(peerFile.readText())
                service.joinVault(peer.getString("pairing"), peer.getString("recovery"), "device-b")
                val synced = service.syncNow()
                assertTrue("B must download the initial merge", synced.appliedOps >= 1)
                assertEquals("A-INIT", container.repository.get(subId)!!.name)
                stateFile.writeText(JSONObject().put("vault", peer.getString("vault")).put("recovery", peer.getString("recovery")).toString())
                dbg().writeText("b_join ok applied=" + synced.appliedOps)
            }
            "b_edit" -> {
                val before = service.status()
                container.repository.upsert(sub("B-EDIT"))
                val after = service.status()
                if (service.syncNow().uploaded < 1) {
                    fail("direct=${container.syncRuntime.isDirect} initError=${service.initError} before=$before after=$after active=${service.activeVault} endpoint=${service.endpoint}")
                }
            }
            "a_converge" -> {
                service.syncNow()
                assertEquals("A must converge on B's edit through the product path", "B-EDIT", container.repository.get(subId)!!.name)
                container.repository.archive(subId)
                assertTrue("tombstone upload", service.syncNow().uploaded >= 1)
                container.repository.upsert(sub("A-CONFLICT"))   // queued locally, not uploaded yet
                assertTrue(service.status().pendingUploads >= 1)
            }
            "b_tombstone_conflict" -> {
                service.syncNow()
                assertTrue("tombstone must reach B", container.repository.get(subId)!!.isDeleted)
                container.repository.upsert(sub("B-CONFLICT"))
                assertTrue("B conflict edit upload", service.syncNow().uploaded >= 1)
            }
            "a_resolve" -> {
                service.syncNow()
                val vault = requireNotNull(service.activeVault)
                val conflicts = service.conflictsRepository().conflicts(vault)
                assertTrue("A must surface the concurrent conflict", conflicts.isNotEmpty())
                assertTrue(service.conflictsRepository().resolveKeepRemote(vault, conflicts.first().operationId))
                assertEquals("keep-remote applies B's value", "B-CONFLICT", container.repository.get(subId)!!.name)
                assertTrue("decision must be enqueued for peers", service.status().pendingUploads >= 1)
                service.syncNow()
                assertEquals(0, service.status().unresolvedConflicts)
            }
            "b_resolve" -> {
                service.syncNow()
                assertEquals("B converges on the published resolution", "B-CONFLICT", container.repository.get(subId)!!.name)
                val vault = requireNotNull(service.activeVault)
                assertEquals("no unresolved conflict after resolution", 0, service.conflictsRepository().conflicts(vault).size)
            }
            "a_revoke" -> {
                val devices = service.devices()
                val peer = devices.first { it.name == "device-b" }
                service.revoke(peer.id)
                assertTrue(service.devices().first { it.id == peer.id }.revoked)
                stateFile.writeText(JSONObject(stateFile.readText()).put("revokedPeer", peer.id).toString())
            }
            "b_revoked" -> {
                val failure = runCatching { service.syncNow() }
                assertNotNull("revoked device must be rejected", failure.exceptionOrNull())
            }
            "a_replay" -> {
                // Replay/idempotency after the whole matrix: repeated sync must not re-fork or resurrect.
                service.syncNow()
                service.syncNow()
                assertEquals("no duplicate divergence after replay", "B-CONFLICT", container.repository.get(subId)!!.name)
                assertEquals(0, service.status().unresolvedConflicts)
            }
            else -> error("unknown syncProductPhase ${phase()}")
        }
        Unit
    }

    private fun dbg() = File(context.filesDir, "sync-product-debug.txt")
}
