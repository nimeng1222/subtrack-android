package com.subtrack

import androidx.test.platform.app.InstrumentationRegistry
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.SyncProductService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * O6 + process-death matrix (isolated; injected only for matrix runs).
 *  - two devices pair; A designates B as the reminder device; A cancels all plans, B takes over;
 *  - A queues an offline edit, the host force-stops the app, and a fresh process resumes & uploads it exactly once.
 */
class SyncRoleAndRestartMatrixDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val subId = "sub_role_matrix_0001"
    private val endpoint = "http://127.0.0.1:18450"
    private val setupToken = com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix()
    private val stateFile get() = File(context.filesDir, "role-state.json")
    private val peerFile get() = File(context.filesDir, "role-state-from-peer.json")
    private val sub get() = Subscription(
        id = subId, name = "ROLE", categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.now().minusDays(1), nextChargeDate = LocalDate.now().plusDays(2), priceMinor = 1000L,
    )

    private fun phase() = requireNotNull(InstrumentationRegistry.getArguments().getString("rolePhase"))

    private suspend fun awaitContainer(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("container not initialised")
    }

    @Test fun run_phase(): Unit = runBlocking {
        val app = awaitContainer()
        val service: SyncProductService = app.syncService
        val db = SubTrackDatabase.get(context)
        when (phase()) {
            "a_create" -> {
                service.setEndpoint(endpoint)
                val created = service.createVault(setupToken, "device-a")
                stateFile.writeText(JSONObject().put("vault", created.vaultId).put("bundle", created.dataKeyRecovery)
                    .put("pairing", service.pairingCredential()).toString())
                app.repository.upsert(sub)
                app.reminderService.reconcile()
                assertTrue("A must schedule before the role switch", db.reminderDao().pendingFor(subId).isNotEmpty())
                assertTrue(service.syncNow().uploaded >= 1)
            }
            "b_join" -> {
                service.setEndpoint(endpoint)
                val peer = JSONObject(peerFile.readText())
                service.joinVault(peer.getString("pairing"), peer.getString("bundle"), "device-b")
                service.syncNow()
                assertEquals("ROLE", app.repository.get(subId)!!.name)
                assertTrue("B schedules while it is the default reminder device", db.reminderDao().pendingAll().isNotEmpty())
            }
            "a_designate_b" -> {
                val devices = service.devices()
                val peer = devices.first { it.name == "device-b" }
                // P5: single product entry point; the decision is published as tenant metadata by the server.
                service.setDesignatedReminderDevice(peer.id)
                assertFalse("A must mute itself after designating B", service.shouldScheduleReminders())
                assertTrue("A's old plans must be cancelled", db.reminderDao().pendingAll().isEmpty())
                assertTrue(service.reminderRoleVersion() >= 1)
            }
            "b_sync_role" -> {
                // B only performs a NORMAL sync; the role arrives as synced tenant metadata (no host file / no setter).
                service.syncNow()
                assertTrue("B must learn it is the designated device through sync", service.shouldScheduleReminders())
                app.reminderService.reconcile()
                assertTrue("B must schedule after role propagation", db.reminderDao().pendingFor(subId).isNotEmpty())
            }
            "a_designate_self" -> {
                service.setDesignatedReminderDevice(service.status().deviceId)
                assertTrue("A takes the role back", service.shouldScheduleReminders())
                app.reminderService.reconcile()
                assertTrue(db.reminderDao().pendingFor(subId).isNotEmpty())
            }
            "b_sync_role_back" -> {
                service.syncNow()
                assertFalse("B must yield the role through sync", service.shouldScheduleReminders())
                app.reminderService.reconcile()
                assertTrue("B's plans must be cancelled", db.reminderDao().pendingAll().isEmpty())
            }
            "a_sync_final" -> {
                service.syncNow()
                assertTrue("A remains the reminder device", service.shouldScheduleReminders())
                app.reminderService.reconcile()
                assertTrue(db.reminderDao().pendingFor(subId).isNotEmpty())
            }
            "restart_prepare" -> {
                // Offline edit: queued in the outbox, NOT synced. The host force-stops the process afterwards.
                app.repository.upsert(sub.copy(name = "ROLE-RESTART"))
                assertTrue(service.status().pendingUploads >= 1)
            }
            "restart_verify" -> {
                assertEquals("the edit must survive process death", "ROLE-RESTART", app.repository.get(subId)!!.name)
                val before = db.syncDao().pendingCount(requireNotNull(service.activeVault))
                assertTrue(before >= 1)
                assertTrue("a fresh process must upload the queued edit", service.syncNow().uploaded >= 1)
                assertEquals("no duplicate queued rows after restart", 0, service.status().pendingUploads)
                assertEquals(0, service.status().unresolvedConflicts)
            }
            else -> error("unknown rolePhase ${phase()}")
        }
        Unit
    }
}
