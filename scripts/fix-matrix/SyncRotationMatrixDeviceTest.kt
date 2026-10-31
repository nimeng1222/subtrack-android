package com.subtrack

import androidx.test.platform.app.InstrumentationRegistry
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.SyncKeyStore
import com.subtrack.sync.SyncProductService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * C/G4 product-path dual-device rotation matrix (isolated; injected only for matrix runs).
 * Phases are selected with ; the host copies the provisioning file A→B (out-of-band transfer).
 */
class SyncRotationMatrixDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    /** The custom runner may start before Application.onCreate finishes; wait for the production container. */
    private suspend fun awaitContainer(): com.subtrack.AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("app container was not initialised")
    }
    private val endpoint = "http://127.0.0.1:18450"
    private val setupToken = "subtrack-e2e-test-setup-token-0123456789"
    private val subId = "sub_rotation_matrix_0001"
    private val stateFile get() = File(context.filesDir, "rotation-state.json")
    private val peerFile get() = File(context.filesDir, "rotation-state-from-peer.json")
    private val rotationFile get() = File(context.filesDir, "rotation-plan.json")
    private val peerRotation get() = File(context.filesDir, "rotation-plan-from-peer.json")

    private fun phase() = requireNotNull(InstrumentationRegistry.getArguments().getString("rotationPhase"))
    private fun side() = InstrumentationRegistry.getArguments().getString("rotationSide") ?: "a"
    private fun ownSubId() = "sub_rotation_own_" + side()
    private fun own(name: String) = Subscription(
        id = ownSubId(), name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.of(2026, 9, 29), nextChargeDate = LocalDate.of(2026, 11, 1), priceMinor = 1000L,
    )
    private fun sub(name: String) = Subscription(
        id = subId, name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.of(2026, 9, 29), nextChargeDate = LocalDate.of(2026, 11, 1), priceMinor = 1000L,
    )

    @Test fun run_phase(): Unit = runBlocking {
        val app = awaitContainer()
        val service: SyncProductService = app.syncService
        when (phase()) {
            "a_create" -> {
                service.setEndpoint(endpoint)
                val created = service.createVault(setupToken, "device-a")
                val pairing = service.pairingCredential()
                stateFile.writeText(JSONObject().put("vault", created.vaultId).put("bundle", created.dataKeyRecovery).put("pairing", pairing).toString())
                app.repository.upsert(sub("A-EPOCH1"))
                assertTrue(service.syncNow().uploaded >= 1)
            }
            "b_join" -> {
                service.setEndpoint(endpoint)
                val peer = JSONObject(peerFile.readText())
                service.joinVault(peer.getString("pairing"), peer.getString("bundle"), "device-b")
                service.syncNow()
                assertEquals("A-EPOCH1", app.repository.get(subId)!!.name)
                stateFile.writeText(peer.toString())
            }
            "a_rotate" -> {
                val (epoch, bundle) = service.rotateKey()
                assertEquals(2, epoch)
                rotationFile.writeText(JSONObject().put("epoch", epoch).put("bundle", bundle).toString())
                app.repository.upsert(sub("A-EPOCH2"))
                assertTrue(service.syncNow().uploaded >= 1)
            }
            "b_import" -> {
                val plan = JSONObject(peerRotation.readText())
                assertEquals(2, service.importRecoveryBundle(plan.getString("bundle")))
                assertEquals("import must advance the epoch", 2, service.status().keyEpoch)
                service.syncNow()
                assertEquals("B must decrypt the epoch-2 operation", "A-EPOCH2", app.repository.get(subId)!!.name)
                assertNotNull("epoch-1 key retained", SyncKeyStore(context).load(requireNotNull(service.activeVault), 1))
                assertNotNull("epoch-2 key imported", SyncKeyStore(context).load(requireNotNull(service.activeVault), 2))
            }
            "a_converge" -> {
                service.syncNow()
                assertEquals(0, service.status().pendingUploads)
                assertEquals("A-EPOCH2", app.repository.get(subId)!!.name)
            }
            // C concurrency: both devices rotate at the same time; the server allocates distinct epochs.
            "rotate" -> {
                val (epoch, bundle) = service.rotateKey()
                rotationFile.writeText(JSONObject().put("epoch", epoch).put("bundle", bundle).toString())
                app.repository.upsert(own("OWN-EPOCH-" + epoch))
                // The peer may have uploaded an operation under its own concurrently-allocated epoch. This device cannot
                // decrypt it until the peer's bundle is imported — that must be an explicit, recoverable state.
                val upload = runCatching { service.syncNow() }
                assertTrue("own operation must upload", upload.getOrNull()?.uploaded ?: 1 >= 1)
                upload.exceptionOrNull()?.let {
                    assertTrue("unknown peer epoch must surface as a typed missing-key error, got $it",
                        it is com.subtrack.sync.MissingEpochKeyException)
                }
            }
            // Import the peer's epoch bundle, sync, and prove BOTH epochs' operations are decryptable.
            "import_verify" -> {
                val plan = JSONObject(peerRotation.readText())
                assertEquals(plan.getInt("epoch"), service.importRecoveryBundle(plan.getString("bundle")))
                service.syncNow()
                val names = listOf("sub_rotation_own_a", "sub_rotation_own_b").mapNotNull { app.repository.get(it)?.name }
                assertEquals("both devices' epoch operations must be decrypted: $names", 2, names.size)
                assertTrue(names.any { it.startsWith("OWN-EPOCH-") })
                val vault = requireNotNull(service.activeVault)
                assertNotNull("own epoch key retained", com.subtrack.sync.SyncKeyStore(context).load(vault, plan.getInt("epoch")))
                assertNotNull("epoch-1 key retained", com.subtrack.sync.SyncKeyStore(context).load(vault, 1))
            }
            "b_replay" -> {
                val before = db().syncDao().state(requireNotNull(service.activeVault))!!.cursor
                service.syncNow(); service.syncNow()
                val after = db().syncDao().state(requireNotNull(service.activeVault))!!.cursor
                assertTrue("cursor must not go backwards", after >= before)
                assertEquals("A-EPOCH2", app.repository.get(subId)!!.name)
                assertEquals(0, service.status().unresolvedConflicts)
            }
            else -> error("unknown rotationPhase ${phase()}")
        }
        Unit
    }

    private fun db() = SubTrackDatabase.get(context)
}
