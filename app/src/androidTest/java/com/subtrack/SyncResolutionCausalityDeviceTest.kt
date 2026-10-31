package com.subtrack

import androidx.room.Room
import androidx.room.withTransaction
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** G2 regressions: an explicit resolution may only eliminate what it causally covers and cites. */
class SyncResolutionCausalityDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_g2_causality"
    private val id = "sub_g2_causality_0001"

    private fun entity(name: String) = SubscriptionEntity(
        id = id, name = name, categoryId = "other", billingCycleType = "MONTHLY", anchorDateEpochDay = 1,
        nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )
    private fun applier(db: SubTrackDatabase) = SyncBusinessApplier(
        db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()),
        { 9L }, db.syncConflictDao(),
    )
    private fun op(opId: String, device: String, counter: Long, name: String, resolves: Set<String> = emptySet(),
                   clock: Map<String, Long> = mapOf(device to counter)) =
        BusinessOperation.SubscriptionUpsert(opId, device, clock, false, entity(name), resolves)
    private suspend fun apply(db: SubTrackDatabase, operation: BusinessOperation) =
        db.withTransaction { applier(db).apply(vault, operation) }

    private fun newDb() = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()

    @Test fun older_resolution_must_not_overwrite_a_later_edit() = runBlocking {
        val db = newDb()
        try {
            apply(db, op("operation_g2_base_000001", "device_a", 1, "BASE"))
            val newer = op("operation_g2_newer_00001", "device_a", 2, "NEWER")
            apply(db, newer)
            val olderDecision = op(
                "operation_g2_older_00001", "device_a", 1, "RESOLVED",
                resolves = setOf("operation_g2_base_000001"),
            )
            assertEquals("a stale decision is a replay, never a rollback", SyncBusinessApplier.Outcome.REPLAY, apply(db, olderDecision))
            assertEquals("NEWER", db.subscriptionDao().findById(id)!!.name)
        } finally { db.close() }
    }

    @Test fun uncited_concurrent_branch_must_survive_another_branch_resolution() = runBlocking {
        val db = newDb()
        try {
            val third = op("operation_g2_third_00001", "device_c", 1, "THIRD")
            apply(db, third)
            val decision = op(
                "operation_g2_resolve_001", "device_a", 1, "RESOLVED",
                resolves = setOf("operation_g2_ab_00000001"),
            )
            // The cited A/B version is NOT known locally: the decision conflicts with the local C branch.
            assertEquals(SyncBusinessApplier.Outcome.CONFLICT, apply(db, decision))
            assertEquals("local concurrent value must not be overwritten", "THIRD", db.subscriptionDao().findById(id)!!.name)
            assertEquals("both heads must survive", 2, db.syncHeadDao().heads(vault, "subscription", id).size)
            assertEquals("conflict material must be surfaced", 1, db.syncConflictDao().countForVault(vault))
        } finally { db.close() }
    }

    @Test fun newest_resolution_covers_and_applies_normally() = runBlocking {
        val db = newDb()
        try {
            val a = op("operation_g2_a_00000001", "device_a", 1, "A")
            val b = op("operation_g2_b_00000001", "device_b", 1, "B")
            apply(db, a); apply(db, b)
            // A real decision merges every head's clock (this is what Causality.nextClock does in production).
            val decision = op("operation_g2_win_000001", "device_a", 3, "WINNER",
                resolves = setOf(a.operationId, b.operationId), clock = mapOf("device_a" to 3L, "device_b" to 1L))
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(db, decision))
            assertEquals("WINNER", db.subscriptionDao().findById(id)!!.name)
            assertEquals(1, db.syncHeadDao().heads(vault, "subscription", id).size)
            assertEquals(0, db.syncConflictDao().countForVault(vault))
        } finally { db.close() }
    }
}
