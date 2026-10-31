package com.subtrack

import androidx.room.Room
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Verifies the remote-apply branch of N4 business sync: causal ordering applies newer edits, concurrent edits become a
 * durable conflict without silently overwriting, tombstones delete, replays are ignored, and applying a remote op never
 * creates a new outbox row (no upload echo loop).
 */
class SyncApplyDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_apply_test"
    private val local = "device_local"
    private val remote = "device_remote"

    private fun entity(id: String, name: String) = SubscriptionEntity(
        id = id, name = name, categoryId = "other", billingCycleType = "MONTHLY",
        anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000, currency = "CNY",
        status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )

    private fun op(id: String, entity: SubscriptionEntity, clock: Map<String, Long>, deleted: Boolean = false) =
        BusinessOperation.SubscriptionUpsert("op_" + UUID.randomUUID().toString().replace("-", ""), remote, clock, deleted, entity)

    private suspend fun SubTrackDatabase.seedHeads(id: String, clock: Map<String, Long>, deleted: Boolean = false) {
        SyncHeadStore(syncHeadDao()).integrate(vault, "subscription", id, CausalVersion("op_seed_" + UUID.randomUUID().toString().replace("-", ""), clock, deleted))
    }

    private fun applier(db: SubTrackDatabase) = SyncBusinessApplier(
        db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()), { 999L },
    )

    @Test fun newer_remote_edit_applies_without_creating_outbox() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val id = "sub_apply_1"
            db.subscriptionDao().upsert(entity(id, "LOCAL"))
            db.seedHeads(id, mapOf(local to 1L))
            val outcome = applier(db).apply(vault, op(id, entity(id, "REMOTE-NEW"), mapOf(local to 2L)))
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, outcome)
            assertEquals("REMOTE-NEW", db.subscriptionDao().findById(id)!!.name)
            assertEquals(0, db.syncDao().pending(vault, 10).size)   // no echo to outbox
            assertEquals(1, db.syncHeadDao().heads(vault, "subscription", id).size)
        } finally { db.close() }
    }

    @Test fun concurrent_edit_becomes_durable_conflict_without_overwrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val id = "sub_apply_2"
            db.subscriptionDao().upsert(entity(id, "LOCAL"))
            db.seedHeads(id, mapOf(local to 1L))
            val outcome = applier(db).apply(vault, op(id, entity(id, "REMOTE-CONFLICT"), mapOf(remote to 1L)))
            assertEquals(SyncBusinessApplier.Outcome.CONFLICT, outcome)
            assertEquals("LOCAL", db.subscriptionDao().findById(id)!!.name)   // not overwritten
            assertEquals(2, db.syncHeadDao().heads(vault, "subscription", id).size) // both heads persisted
            assertEquals(0, db.syncDao().pending(vault, 10).size)
        } finally { db.close() }
    }

    @Test fun remote_tombstone_deletes_and_older_replay_is_ignored() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val id = "sub_apply_3"
            db.subscriptionDao().upsert(entity(id, "LOCAL"))
            db.seedHeads(id, mapOf(local to 1L))
            // older replay ignored
            assertEquals(SyncBusinessApplier.Outcome.REPLAY,
                applier(db).apply(vault, op(id, entity(id, "OLD"), mapOf(local to 1L).let { it })))
            // proper tombstone after local head
            val tomb = entity(id, "LOCAL").copy(deletedAtUtcMillis = 777L)
            assertEquals(SyncBusinessApplier.Outcome.APPLIED,
                applier(db).apply(vault, op(id, tomb, mapOf(local to 1L, remote to 3L), deleted = true)))
            assertEquals(777L, db.subscriptionDao().findById(id)!!.deletedAtUtcMillis)
            assertEquals(0, db.syncDao().pending(vault, 10).size)
        } finally { db.close() }
    }

    @Test fun already_seen_operation_is_idempotent_replay() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val id = "sub_apply_4"
            db.subscriptionDao().upsert(entity(id, "LOCAL"))
            val op = op(id, entity(id, "REMOTE"), mapOf(local to 2L))
            val first = applier(db).apply(vault, op)
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, first)
            val second = applier(db).apply(vault, op)   // exact replay
            assertEquals(SyncBusinessApplier.Outcome.REPLAY, second)
            assertEquals("REMOTE", db.subscriptionDao().findById(id)!!.name)
        } finally { db.close() }
    }
}
