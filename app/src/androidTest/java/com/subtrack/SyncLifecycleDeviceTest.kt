package com.subtrack

import androidx.room.Room
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.network.NetworkTransport
import com.subtrack.sync.OutboxSyncWriteGateway
import com.subtrack.sync.SyncEngine
import com.subtrack.sync.SyncHeadStore
import com.subtrack.sync.SyncProductService
import com.subtrack.sync.SyncSessionLostException
import com.subtrack.sync.SyncVaultLocks
import com.subtrack.sync.SyncWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** N1: admission must hold at the END of the commit transaction too, not only at its entry. */
class SyncLifecycleDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
    private fun sub(id: String) = SubscriptionEntity(
        id = id, name = "n", categoryId = "other", billingCycleType = "MONTHLY", anchorDateEpochDay = 1,
        nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )
    private val keyProvider: suspend (Int) -> ByteArray = { _: Int -> ByteArray(32) { 5 } }

    @Test fun admission_is_rechecked_at_the_end_of_the_commit_transaction() = runBlocking {
        val db = db()
        try {
            val checks = AtomicInteger(0)
            // First call = transaction entry (allowed); second call = end of transaction (session lost meanwhile).
            val admission: suspend () -> Boolean = { checks.incrementAndGet() == 1 }
            val gateway = OutboxSyncWriteGateway(
                "vault_n1_end", "device_a", SyncHeadStore(db.syncHeadDao()),
                SyncEngine(db, SyncWire("http://127.0.0.1:18450", NetworkTransport(debug = true))) { admission() },
                keyProvider, admission = admission,
            )
            val error = runCatching { gateway.subscription(sub("sub_n1_end_0001"), false) { } }.exceptionOrNull()
            assertTrue("a session lost during the transaction must roll it back: $error", error is SyncSessionLostException)
            assertTrue("admission must be checked more than once", checks.get() >= 2)
            assertEquals("ledger mutation must roll back", null, db.subscriptionDao().findById("sub_n1_end_0001"))
            assertEquals("no outbox row may survive the rollback", 0, db.syncDao().pending("vault_n1_end", 10).size)
        } finally { db.close() }
    }

    @Test fun switching_the_service_endpoint_invalidates_the_old_session() = runBlocking {
        val container = awaitContainer()
        val service: SyncProductService = container.syncService
        val original = service.endpoint
        val fake = com.subtrack.sync.SyncSession(
            "http://127.0.0.1:18450", "vault_n2_switch", "dev_a", "token_a_token_a_token_a_token_a", keyProvider,
        )
        container.syncRuntime.bind(fake, com.subtrack.sync.OutboxSyncWriteGateway(
            fake.vaultId, fake.deviceId, SyncHeadStore(SubTrackDatabase.get(context).syncHeadDao()),
            SyncEngine(SubTrackDatabase.get(context), SyncWire(fake.endpoint, NetworkTransport(debug = true))) { container.syncRuntime.isCurrent(fake) },
            keyProvider, admission = { container.syncRuntime.isCurrent(fake) },
        ))
        assertFalse(container.syncRuntime.isDirect)
        val target = "http://127.0.0.1:18451"
        val bound = service.switchEndpoint(target)
        assertFalse("no credential exists on the new endpoint, so it must NOT silently rebind", bound)
        assertFalse("old session must no longer be current", container.syncRuntime.isCurrent(fake))
        assertEquals(target, service.endpoint)
        // Restore the original endpoint for the rest of the suite.
        if (original != null && original != target) service.switchEndpoint(original)
    }

    @Test fun designated_reminder_device_controls_scheduling() = runBlocking {
        val service = awaitContainer().syncService
        val before = service.designatedReminderDeviceId
        try {
            assertTrue("single-device default schedules", service.shouldScheduleReminders())
            service.setDesignatedReminderDevice("other-device")
            assertFalse("a non-designated device must not schedule", service.shouldScheduleReminders())
            // Persisted across instances (a fresh service reads the same prefs).
            val fresh = SyncProductService(context, SubTrackDatabase.get(context), awaitContainer().syncRuntime)
            assertFalse(fresh.shouldScheduleReminders())
        } finally {
            service.setDesignatedReminderDevice(before)
        }
    }

    private suspend fun awaitContainer(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("container not initialised")
    }

    @Test fun concurrent_rotations_get_distinct_server_epochs() = runBlocking {
        val app = awaitContainer()
        val service: SyncProductService = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix(), "device-rotate")
        val scope = CoroutineScope(Dispatchers.Default)
        val results = (1..2).map { scope.async { service.rotateKey() } }.map { it.await() }
        scope.cancel()
        val epochs = results.map { it.first }
        assertEquals("server must allocate distinct epochs", 2, epochs.distinct().size)
        val vault = requireNotNull(service.activeVault)
        for ((epoch, bundle) in results) {
            assertEquals(epoch, com.subtrack.sync.SyncRecoveryBundle.parse(bundle).epoch)
            assertNotNull("key for allocated epoch must exist", com.subtrack.sync.SyncKeyStore(context).load(vault, epoch))
        }
        assertTrue(service.status().keyEpoch!! >= epochs.max())
    }
}
