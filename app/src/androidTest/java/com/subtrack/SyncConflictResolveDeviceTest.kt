package com.subtrack

import androidx.room.Room
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * N4 conflict resolution: a concurrent remote op is recorded (not applied); the user can then deterministically
 * "keep remote" (apply it, sole head) or "keep local" (drop it). Verifies durable, user-resolvable conflict handling
 * rather than silent last-write-wins.
 */
class SyncConflictResolveDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_conflict_test"
    private val local = "device_local"
    private val remote = "device_remote"

    private fun entity(id: String, name: String) = SubscriptionEntity(
        id = id, name = name, categoryId = "other", billingCycleType = "MONTHLY",
        anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000, currency = "CNY",
        status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )
    private fun applier(db: SubTrackDatabase) = SyncBusinessApplier(
        db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()), { 9L }, db.syncConflictDao(),
    )
    private fun resolver(db: SubTrackDatabase) = SyncConflictRepository(db, db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao())

    private suspend fun seedLocal(db: SubTrackDatabase, id: String, name: String) {
        db.subscriptionDao().upsert(entity(id, name))
        SyncHeadStore(db.syncHeadDao()).integrate(vault, "subscription", id, CausalVersion("operation_seed00000001", mapOf(local to 1L), false))
    }
    private fun remoteOp(id: String, name: String, opId: String) =
        BusinessOperation.SubscriptionUpsert(opId, remote, mapOf(remote to 1L), false, entity(id, name))

    @Test fun keep_remote_applies_stored_remote_and_clears_conflict() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val id = "sub_conflict_remote1"
            val opId = "operation_conflict0001"
            seedLocal(db, id, "LOCAL")
            assertEquals(SyncBusinessApplier.Outcome.CONFLICT, applier(db).apply(vault, remoteOp(id, "REMOTE", opId)))
            assertEquals(1, db.syncConflictDao().countForVault(vault))
            assertEquals(2, db.syncHeadDao().heads(vault, "subscription", id).size)
            assertEquals(1, resolver(db).conflicts(vault).size)

            assertTrue(resolver(db).resolveKeepRemote(vault, opId))
            assertEquals("REMOTE", db.subscriptionDao().findById(id)!!.name)
            assertEquals(1, db.syncHeadDao().heads(vault, "subscription", id).size)
            assertEquals(opId, db.syncHeadDao().heads(vault, "subscription", id).single().headId)
            assertEquals(0, db.syncConflictDao().countForVault(vault))
        } finally { db.close() }
    }

    @Test fun keep_local_drops_remote_and_preserves_local() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val id = "sub_conflict_local01"
            val opId = "operation_conflict0002"
            seedLocal(db, id, "LOCAL")
            assertEquals(SyncBusinessApplier.Outcome.CONFLICT, applier(db).apply(vault, remoteOp(id, "REMOTE", opId)))
            assertTrue(resolver(db).resolveKeepLocal(vault, opId))
            assertEquals("LOCAL", db.subscriptionDao().findById(id)!!.name)
            assertEquals(1, db.syncHeadDao().heads(vault, "subscription", id).size)
            assertEquals(0, db.syncConflictDao().countForVault(vault))
        } finally { db.close() }
    }
}
