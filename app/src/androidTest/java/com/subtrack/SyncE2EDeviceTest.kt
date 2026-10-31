package com.subtrack

import androidx.room.Room
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.repository.SubscriptionRepositoryImpl
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.network.NetworkTransport
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * N4 end-to-end integration on ONE emulator: TWO independent Room databases + TWO device credentials + the real
 * `SyncEngine`/`SyncCoordinator`/`SyncBusinessApplier`/`SyncCrypto` against the REAL Python sync server.
 *
 * HONEST SCOPE: single emulator, two in-process client stores — this exercises the full client pipeline end-to-end
 * (upload/download ordering, encryption, causal apply, tombstone, revoke), which is far stronger than a two-token wire
 * fixture, but it is NOT two physical Android devices; that remains an external-condition item.
 *
 * Requires the sync server reachable at http://127.0.0.1:18450 with the fixed test setup token (adb reverse).
 */
class SyncE2EDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val base = "http://127.0.0.1:18450"
    companion object {
        /** Same fabricated, test-only setup token; shared with the disabled-publish matrix tests. */
        internal fun setupTokenForMatrix(): String = "subtrack-e2e-test-setup-token-0123456789"
    }

    private val setupToken = "subtrack-e2e-test-setup-token-0123456789"
    private val today = LocalDate.of(2026, 10, 27)
    private val clock = object : AppClock {
        override fun today() = today
        override fun nowMillis() = 1_700_000_000_000L
        override fun reminderTime() = LocalTime.of(9, 0)
    }

    private fun sub(id: String, name: String) = Subscription(
        id = id, name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = today.minusMonths(1), nextChargeDate = today, priceMinor = 1000L,
    )

    private fun repoFor(db: SubTrackDatabase, vault: String, deviceId: String, key: suspend (Int) -> ByteArray): SubscriptionRepositoryImpl {
        val engine = SyncEngine(db, SyncWire(base, NetworkTransport(true)))   // enqueue uses no network
        val gw = OutboxSyncWriteGateway(vault, deviceId, SyncHeadStore(db.syncHeadDao()), engine, key)
        return SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db, gw)
    }

    private suspend fun sync(db: SubTrackDatabase, vault: String, token: String, key: suspend (Int) -> ByteArray): SyncCoordinator.Result {
        // Fresh engine/transport per call keeps clear of the transport's per-URL 2s throttle across phases.
        val engine = SyncEngine(db, SyncWire(base, NetworkTransport(true)))
        val applier = SyncBusinessApplier(
            db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()),
            { clock.nowMillis() }, db.syncConflictDao(),
        )
        return engine.coordinator(key) { _, bytes -> applier.apply(vault, BusinessCodec.decode(bytes)) }.syncOnce(vault, token)
    }

    @Test fun two_independent_clients_sync_business_operations_via_real_server() = runBlocking {
        val wire = SyncWire(base, NetworkTransport(true))
        val reg = wire.register(setupToken, "clientA")
        val vault = reg.device.tenantId
        val devA = reg.device.deviceId
        val tokenA = reg.device.token
        val key: suspend (Int) -> ByteArray = { SyncCrypto.parseRecoveryKey(reg.recoveryCredential) }

        val dbA = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            dbA.syncDao().putState(SyncStateEntity(vault, devA, 1, 0, enabled = true))
            val id = "sub_e2e_000000000001"
            val repoA = repoFor(dbA, vault, devA, key)
            repoA.upsert(sub(id, "E2E-A"))
            assertEquals(1, sync(dbA, vault, tokenA, key).uploaded)

            // Client B pairs via a single-use pairing credential → own device id + token, same tenant + E2EE key.
            val pairing = SyncWire(base, NetworkTransport(true)).pairingToken(tokenA)
            val credB = SyncWire(base, NetworkTransport(true)).pair(pairing, "clientB")
            val devB = credB.deviceId
            val tokenB = credB.token
            dbB.syncDao().putState(SyncStateEntity(vault, devB, 1, 0, enabled = true))

            // B downloads + applies A's subscription.
            assertEquals(1, sync(dbB, vault, tokenB, key).appliedOps)
            assertEquals("E2E-A", dbB.subscriptionDao().findById(id)!!.name)

            // B edits (its head now causally includes A's version), syncs; A reconciles the edit.
            val repoB = repoFor(dbB, vault, devB, key)
            repoB.upsert(sub(id, "E2E-B-EDIT"))
            assertEquals(1, sync(dbB, vault, tokenB, key).uploaded)
            sync(dbA, vault, tokenA, key)
            assertEquals("E2E-B-EDIT", dbA.subscriptionDao().findById(id)!!.name)

            // A deletes → tombstone propagates to B.
            repoA.archive(id)
            sync(dbA, vault, tokenA, key)
            sync(dbB, vault, tokenB, key)
            assertNotNull("tombstone must reach B", dbB.subscriptionDao().findById(id)!!.deletedAtUtcMillis)

            // Revoke B → B's further sync is rejected by the server.
            SyncWire(base, NetworkTransport(true)).revoke(tokenA, devB)
            val rejected = runCatching { sync(dbB, vault, tokenB, key) }
            assertNotNull("revoked device must be rejected", rejected.exceptionOrNull())
        } finally {
            dbA.close(); dbB.close()
        }
    }

    /**
     * Multiple queued business ops must drain in ONE reconcile without tripping the transport's per-URL 2s throttle
     * (bounded spacing, no busy-loop) and converge causally on the peer. Without spacing the 2nd same-URL upload would
     * throw "请求过于频繁"; this asserts all three upload and the peer ends on the last edit.
     */
    @Test fun multiple_queued_ops_drain_with_spacing_and_converge() = runBlocking {
        val wire = SyncWire(base, NetworkTransport(true))
        val reg = wire.register(setupToken, "clientA2")
        val vault = reg.device.tenantId; val devA = reg.device.deviceId; val tokenA = reg.device.token
        val key: suspend (Int) -> ByteArray = { SyncCrypto.parseRecoveryKey(reg.recoveryCredential) }
        val dbA = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            dbA.syncDao().putState(SyncStateEntity(vault, devA, 1, 0, enabled = true))
            val id = "sub_e2e_multi0000001"
            repoFor(dbA, vault, devA, key).upsert(sub(id, "INIT"))
            assertEquals(1, sync(dbA, vault, tokenA, key).uploaded)

            val pairing = SyncWire(base, NetworkTransport(true)).pairingToken(tokenA)
            val credB = SyncWire(base, NetworkTransport(true)).pair(pairing, "clientB2")
            dbB.syncDao().putState(SyncStateEntity(vault, credB.deviceId, 1, 0, enabled = true))
            sync(dbB, vault, credB.token, key)   // B applies INIT

            // Queue three edits locally, then drain all in one reconcile.
            val repoB = repoFor(dbB, vault, credB.deviceId, key)
            repoB.upsert(sub(id, "V1")); repoB.upsert(sub(id, "V2")); repoB.upsert(sub(id, "V3"))
            assertEquals(3, sync(dbB, vault, credB.token, key).uploaded)

            sync(dbA, vault, tokenA, key)
            assertEquals("V3", dbA.subscriptionDao().findById(id)!!.name)
        } finally { dbA.close(); dbB.close() }
    }

    /**
     * More ops than one server page: A queues 12 subscriptions → uploaded in batches of [pageLimit] with bounded spacing
     * (no throttle trip); B downloads across MULTIPLE pages (10 + 2) and applies all, advancing checkpoints page by page.
     */
    @Test fun batched_upload_and_multi_page_download_apply_all() = runBlocking {
        val wire = SyncWire(base, NetworkTransport(true))
        val reg = wire.register(setupToken, "clientA3")
        val vault = reg.device.tenantId; val devA = reg.device.deviceId; val tokenA = reg.device.token
        val key: suspend (Int) -> ByteArray = { SyncCrypto.parseRecoveryKey(reg.recoveryCredential) }
        val dbA = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            dbA.syncDao().putState(SyncStateEntity(vault, devA, 1, 0, enabled = true))
            val repoA = repoFor(dbA, vault, devA, key)
            for (i in 1..12) repoA.upsert(sub("sub_page_%012d".format(i), "S$i"))
            assertEquals(12, sync(dbA, vault, tokenA, key).uploaded)

            val pairing = SyncWire(base, NetworkTransport(true)).pairingToken(tokenA)
            val credB = SyncWire(base, NetworkTransport(true)).pair(pairing, "clientB3")
            dbB.syncDao().putState(SyncStateEntity(vault, credB.deviceId, 1, 0, enabled = true))
            val down = sync(dbB, vault, credB.token, key)
            assertEquals(12, down.appliedOps)
            assertEquals(2, down.pages)   // pageLimit=10 → 10 + 2
            assertEquals(12, dbB.subscriptionDao().count())
        } finally { dbA.close(); dbB.close() }
    }
}
