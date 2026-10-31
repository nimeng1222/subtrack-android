package com.subtrack

import androidx.room.Room
import androidx.room.withTransaction
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.network.NetworkTransport
import com.subtrack.sync.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * F2/F3/F4 regressions (converted from the independent acceptance probes):
 *  - a newer edit must not resurrect an unresolved tombstone, and the tombstone state must survive in the ledger;
 *  - a tombstone for an entity this device has never seen must still create a ledger tombstone;
 *  - category edits must actually update existing rows (seed inserts stay IGNORE-only);
 *  - concurrent local writes must allocate distinct causal clocks inside the real public write entry point.
 */
class SyncLedgerConsistencyDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_ledger_consistency"
    private val entityId = "sub_ledger_consistency_01"
    private val key = ByteArray(32) { 9 }
    private val clock = object : AppClock {
        override fun today() = LocalDate.of(2026, 10, 27)
        override fun nowMillis() = 1_700_000_000_000L
        override fun reminderTime() = LocalTime.of(9, 0)
    }

    private fun sub(name: String, deletedAt: Long? = null) = SubscriptionEntity(
        id = entityId, name = name, categoryId = "other", billingCycleType = "MONTHLY",
        anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE",
        createdAtUtcMillis = 1, updatedAtUtcMillis = 1, deletedAtUtcMillis = deletedAt,
    )
    private fun applier(db: SubTrackDatabase) = SyncBusinessApplier(
        db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()),
        { clock.nowMillis() }, db.syncConflictDao(),
    )
    private fun engine(db: SubTrackDatabase) = SyncEngine(db, SyncWire("http://127.0.0.1:18450", NetworkTransport(true)))
    private fun resolver(db: SubTrackDatabase) = SyncConflictRepository(
        db, db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(),
        ResolutionPublisher { v, op, mutate -> engine(db).enqueueBusiness(v, op, { key.copyOf() }, mutate) },
    )
    private fun configure(db: SubTrackDatabase, device: String = "device_a") = runBlocking {
        db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, enabled = true))
    }
    private fun subOp(opId: String, counter: Long, name: String, deleted: Boolean = false, deletedAt: Long? = null) =
        BusinessOperation.SubscriptionUpsert(opId, "device_a", mapOf("device_a" to counter), deleted, sub(name, deletedAt))
    private suspend fun apply(db: SubTrackDatabase, op: BusinessOperation) =
        db.withTransaction { applier(db).apply(vault, op) }

    @Test fun newer_edit_after_unresolved_tombstone_stays_deleted_and_reports_conflict() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(db)
            db.subscriptionDao().upsert(sub("EXISTING"))
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(db, subOp("operation_tombstone_01", 1, "DELETED", deleted = true, deletedAt = 5L)))
            assertNotNull(db.subscriptionDao().findById(entityId)!!.deletedAtUtcMillis)

            val outcome = apply(db, subOp("operation_later_edit_01", 2, "RESURRECTED"))
            assertEquals("protected delete must surface a conflict instead of APPLIED", SyncBusinessApplier.Outcome.CONFLICT, outcome)
            assertNotNull("ledger must remain deleted", db.subscriptionDao().findById(entityId)!!.deletedAtUtcMillis)
            assertEquals("the un-applied payload must be visible for resolution", 1, db.syncConflictDao().countForVault(vault))
            assertEquals("tombstone head is protected next to the incoming head", 2, db.syncHeadDao().heads(vault, "subscription", entityId).size)
            // Replay of the later edit is ignored, never resurrects.
            assertEquals(SyncBusinessApplier.Outcome.REPLAY, apply(db, subOp("operation_later_edit_01", 2, "RESURRECTED")))
            assertNotNull(db.subscriptionDao().findById(entityId)!!.deletedAtUtcMillis)
        } finally { db.close() }
    }

    @Test fun tombstone_for_unknown_entity_creates_ledger_row() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(db)
            assertEquals(
                SyncBusinessApplier.Outcome.APPLIED,
                apply(db, subOp("operation_unknown_tomb_01", 1, "NEVER_SEEN", deleted = true, deletedAt = 7L)),
            )
            assertNotNull("ledger tombstone row must exist", db.subscriptionDao().findById(entityId))
            assertEquals(7L, db.subscriptionDao().findById(entityId)!!.deletedAtUtcMillis)
            assertEquals(1, db.syncHeadDao().heads(vault, "subscription", entityId).size)
        } finally { db.close() }
    }

    @Test fun explicit_resolution_restores_deleted_subscription() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(db)
            db.subscriptionDao().upsert(sub("EXISTING"))
            apply(db, subOp("operation_tombstone_02", 1, "DELETED", deleted = true, deletedAt = 5L))
            assertEquals(SyncBusinessApplier.Outcome.CONFLICT, apply(db, subOp("operation_later_edit_02", 2, "RESTORED")))
            val conflict = db.syncConflictDao().listForVault(vault).single()

            assertTrue(resolver(db).resolveKeepRemote(vault, conflict.operationId))
            assertNull("explicit decision may restore the row", db.subscriptionDao().findById(entityId)!!.deletedAtUtcMillis)
            assertEquals("RESTORED", db.subscriptionDao().findById(entityId)!!.name)
            assertEquals(1, db.syncHeadDao().heads(vault, "subscription", entityId).size)
            assertEquals(0, db.syncConflictDao().countForVault(vault))
            assertEquals("decision must be published", 1, db.syncDao().pending(vault, 10).size)
        } finally { db.close() }
    }

    @Test fun newer_category_edit_updates_existing_row_and_keeps_references() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(db)
            val seeded = CategoryEntity("custom_category", "OLD", "play", 1, false, 1)
            db.categoryDao().insertAll(listOf(seeded))
            db.subscriptionDao().upsert(sub("USES_CUSTOM").copy(categoryId = seeded.id))

            val op = BusinessOperation.CategoryUpsert("operation_category_01", "device_a", mapOf("device_a" to 1L), false, seeded)
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(db, op))
            val rename = op.copy(operationId = "operation_category_02", clock = mapOf("device_a" to 2L), entity = seeded.copy(name = "NEW", colorArgb = 42))
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(db, rename))
            assertEquals("NEW", db.categoryDao().findById(seeded.id)!!.name)
            assertEquals(42, db.categoryDao().findById(seeded.id)!!.colorArgb)
            assertEquals("subscription reference must stay intact", seeded.id, db.subscriptionDao().findById(entityId)!!.categoryId)

            // Seed inserts remain IGNORE-only: they must not overwrite a newer synced value.
            db.categoryDao().insertAll(listOf(seeded.copy(name = "SEED_AGAIN")))
            assertEquals("NEW", db.categoryDao().findById(seeded.id)!!.name)
        } finally { db.close() }
    }

    @Test fun concurrent_local_writes_allocate_distinct_clocks_and_both_commit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(db)
            val gateway = OutboxSyncWriteGateway(
                vault, "device_a", SyncHeadStore(db.syncHeadDao()), engine(db), { key.copyOf() },
            )
            val barrier = CompletableDeferred<Unit>()
            val started = AtomicInteger()
            // Barrier is BEFORE the public write entry point, so both callers really race (the old probe placed it
            // inside keyProvider, which only worked because the buggy implementation scheduled both writes at once).
            val outcomes = coroutineScope {
                (1..2).map { n ->
                    async(Dispatchers.IO) {
                        if (started.incrementAndGet() == 2) barrier.complete(Unit)
                        barrier.await()
                        runCatching {
                            gateway.subscription(sub("edit$n"), deleted = false) { db.subscriptionDao().upsert(sub("edit$n")) }
                        }
                    }
                }.awaitAll()
            }
            assertTrue("both concurrent valid writes must commit: ${outcomes.map { it.exceptionOrNull()?.message }}",
                outcomes.all { it.isSuccess })

            val rows = db.syncDao().pending(vault, 10)
            assertEquals(2, rows.size)
            val clocks = rows.map { row ->
                val plain = SyncCrypto.decrypt(key.copyOf(), vault, row.operationId, SyncCrypto.Envelope(keyEpoch = row.keyEpoch, nonce = row.nonce, ciphertext = row.ciphertext))
                BusinessCodec.decode(plain).clock
            }
            assertEquals("causal clocks must be distinct and ordered", 2, clocks.distinct().size)
            assertEquals(mapOf("device_a" to 1L), clocks.minByOrNull { it.getValue("device_a") })
            assertEquals(mapOf("device_a" to 2L), clocks.maxByOrNull { it.getValue("device_a") })
            assertEquals("single winning head", 1, db.syncHeadDao().heads(vault, "subscription", entityId).size)
        } finally { db.close() }
    }

    @Test fun failed_business_write_rolls_back_outbox_and_head() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(db)
            val gateway = OutboxSyncWriteGateway(
                vault, "device_a", SyncHeadStore(db.syncHeadDao()), engine(db), { key.copyOf() },
            )
            val failure = runCatching {
                gateway.subscription(sub("BROKEN"), deleted = false) { throw IllegalArgumentException("intentional") }
            }
            assertNotNull(failure.exceptionOrNull())
            assertEquals(0, db.syncDao().pendingCount(vault))
            assertEquals(0, db.syncHeadDao().heads(vault, "subscription", "sub_ledger_consistency_01").size)
        } finally { db.close() }
    }

}
