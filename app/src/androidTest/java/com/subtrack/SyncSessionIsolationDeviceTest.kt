package com.subtrack

import androidx.room.Room
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.network.NetworkTransport
import com.subtrack.sync.OutboxSyncWriteGateway
import com.subtrack.sync.SyncEngine
import com.subtrack.sync.SyncHeadStore
import com.subtrack.sync.SyncSession
import com.subtrack.sync.SyncSessionLostException
import com.subtrack.sync.SyncVaultLocks
import com.subtrack.sync.SwitchableSyncWriteGateway
import com.subtrack.sync.SyncWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B/G5: a task started under one immutable session must never commit into a newer session (switch/disable), and every
 * entry point shares one per-vault lock instead of creating an instance lock per coordinator.
 */
class SyncSessionIsolationDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext

    private fun db() = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
    private fun sub(id: String) = SubscriptionEntity(
        id = id, name = "n", categoryId = "other", billingCycleType = "MONTHLY", anchorDateEpochDay = 1,
        nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )

    private fun gatewayFor(
        runtime: SwitchableSyncWriteGateway,
        session: SyncSession,
        db: SubTrackDatabase,
    ): OutboxSyncWriteGateway = OutboxSyncWriteGateway(
        session.vaultId, session.deviceId, SyncHeadStore(db.syncHeadDao()),
        SyncEngine(db, SyncWire(session.endpoint, NetworkTransport(debug = true))) { runtime.isCurrent(session) },
        session.keyProvider,
        admission = { runtime.isCurrent(session) },
    )

    private fun keyProvider(): suspend (Int) -> ByteArray = { _: Int -> ByteArray(32) { 7 } }

    @Test fun write_under_a_replaced_session_is_rejected_without_touching_the_new_vault() = runBlocking {
        val runtime = SwitchableSyncWriteGateway()
        val db = db()
        try {
            val a = SyncSession("http://127.0.0.1:18450", "vault_iso_a", "dev_a", "token_a_token_a_token_a_token_a", keyProvider())
            gatewayFor(runtime, a, db).also { runtime.bind(a, it) }
            val b = SyncSession("http://127.0.0.1:18450", "vault_iso_b", "dev_b", "token_b_token_b_token_b_token_b", keyProvider())
            gatewayFor(runtime, b, db).also { runtime.bind(b, it) }
            assertTrue(runtime.isCurrent(b)); assertFalse(runtime.isCurrent(a))
            val error = runCatching {
                OutboxSyncWriteGateway("vault_iso_a", "dev_a", SyncHeadStore(db.syncHeadDao()),
                    SyncEngine(db, SyncWire("http://127.0.0.1:18450", NetworkTransport(debug = true))) { runtime.isCurrent(a) },
                    keyProvider(), admission = { runtime.isCurrent(a) }
                ).subscription(sub("sub_iso_0001"), false) { }
            }.exceptionOrNull()
            assertTrue("stale session must fail loudly: $error", error is SyncSessionLostException)
            assertEquals("no outbox row may be created for the stale session", 0, db.syncDao().pending("vault_iso_a", 10).size)
        } finally { db.close() }
    }

    @Test fun disable_invalidates_the_frozen_session() = runBlocking {
        val runtime = SwitchableSyncWriteGateway()
        val db = db()
        try {
            val a = SyncSession("http://127.0.0.1:18450", "vault_iso_disable", "dev_a", "token_a_token_a_token_a_token_a", keyProvider())
            gatewayFor(runtime, a, db).also { runtime.bind(a, it) }
            runtime.reset()
            assertFalse(runtime.isCurrent(a))
            val error = runCatching {
                gatewayFor(runtime, a, db).subscription(sub("sub_iso_0002"), false) { }
            }.exceptionOrNull()
            assertTrue(error is SyncSessionLostException)
            assertEquals(0, db.syncDao().pending("vault_iso_disable", 10).size)
        } finally { db.close() }
    }

    @Test fun every_entry_point_shares_one_lock_per_vault() = runBlocking {
        val vault = "vault_iso_lock"
        val inside = AtomicBoolean(false)
        val overlapped = AtomicBoolean(false)
        val scope = CoroutineScope(Dispatchers.Default)
        val jobs = (1..4).map {
            scope.async {
                SyncVaultLocks.forVault(vault).withLock {
                    if (!inside.compareAndSet(false, true)) overlapped.set(true)
                    kotlinx.coroutines.delay(40)
                    inside.set(false)
                }
            }
        }
        jobs.forEach { it.await() }
        scope.cancel()
        assertFalse("per-vault lock must serialise every entry point", overlapped.get())
        // Same vault always yields the same lock instance; different vaults do not.
        assertSame(SyncVaultLocks.forVault(vault), SyncVaultLocks.forVault(vault))
        assertNotSame(SyncVaultLocks.forVault("vault_iso_lock_other"), SyncVaultLocks.forVault(vault))
    }
}
