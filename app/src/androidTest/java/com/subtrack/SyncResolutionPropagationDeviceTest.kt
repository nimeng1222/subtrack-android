package com.subtrack

import androidx.room.Room
import androidx.room.withTransaction
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.network.NetworkTransport
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * F1 regression (converted from the independent acceptance probes and extended to two devices): a conflict decision must
 * itself be a new, encrypted, causally-covering operation so peers converge without manually choosing the same side.
 */
class SyncResolutionPropagationDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_resolution_propagation"
    private val key = ByteArray(32) { 7 }
    private val entityId = "sub_resolution_0001"
    private val clock = object : AppClock {
        override fun today() = LocalDate.of(2026, 10, 27)
        override fun nowMillis() = 1_700_000_000_000L
        override fun reminderTime() = LocalTime.of(9, 0)
    }

    private fun sub(id: String = entityId, name: String, deletedAt: Long? = null) = SubscriptionEntity(
        id = id, name = name, categoryId = "other", billingCycleType = "MONTHLY",
        anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE",
        createdAtUtcMillis = 1, updatedAtUtcMillis = 1, deletedAtUtcMillis = deletedAt,
    )
    private fun applier(db: SubTrackDatabase) = SyncBusinessApplier(
        db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()),
        { clock.nowMillis() }, db.syncConflictDao(),
    )
    private fun engine(db: SubTrackDatabase) =
        SyncEngine(db, SyncWire("http://127.0.0.1:18450", NetworkTransport(true)))
    private fun resolver(db: SubTrackDatabase, publish: Boolean = true) = SyncConflictRepository(
        db, db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(),
        if (publish) ResolutionPublisher { v, op, mutate -> engine(db).enqueueBusiness(v, op, { key.copyOf() }, mutate) } else null,
    )

    private fun subOp(opId: String, device: String, counter: Long, name: String, deleted: Boolean = false, deletedAt: Long? = null) =
        BusinessOperation.SubscriptionUpsert(opId, device, mapOf(device to counter), deleted, sub(name = name, deletedAt = deletedAt))

    private fun configure(db: SubTrackDatabase, device: String) = runBlocking {
        db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, enabled = true))
    }
    private suspend fun apply(db: SubTrackDatabase, op: BusinessOperation) =
        db.withTransaction { applier(db).apply(vault, op) }

    /** Reads the single published resolution out of the encrypted outbox and authenticates it like a peer would. */
    private fun published(db: SubTrackDatabase): BusinessOperation = runBlocking {
        val rows = db.syncDao().pending(vault, 100)
        assertEquals("exactly one published resolution", 1, rows.size)
        val row = rows.single()
        val plain = SyncCrypto.decrypt(key.copyOf(), vault, row.operationId, SyncCrypto.Envelope(keyEpoch = row.keyEpoch, nonce = row.nonce, ciphertext = row.ciphertext))
        BusinessCodec.decode(plain)
    }

    @Test fun unilateral_keep_remote_decision_is_published_and_peer_converges() = runBlocking {
        val a = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(a, "device_a"); configure(b, "device_b")
            val opA = subOp("operation_a_keep_remote_01", "device_a", 1, "A")
            val opB = subOp("operation_b_keep_remote_02", "device_b", 1, "B")
            a.withTransaction { applier(a).apply(vault, opA); applier(a).apply(vault, opB) }
            b.withTransaction { applier(b).apply(vault, opB); applier(b).apply(vault, opA) }
            assertEquals(1, a.syncConflictDao().countForVault(vault))

            assertTrue(resolver(a).resolveKeepRemote(vault, opB.operationId))
            assertEquals("B", a.subscriptionDao().findById(entityId)!!.name)
            val decision = published(a)
            assertTrue(decision is BusinessOperation.SubscriptionUpsert)
            assertEquals("B", (decision as BusinessOperation.SubscriptionUpsert).entity.name)
            assertEquals(setOf(opA.operationId, opB.operationId), decision.resolves)

            // Peer converges with no manual choice.
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(b, decision))
            assertEquals("B", b.subscriptionDao().findById(entityId)!!.name)
            assertEquals(1, b.syncHeadDao().heads(vault, "subscription", entityId).size)
            assertEquals(0, b.syncConflictDao().countForVault(vault))
            // Replay of the same decision is idempotent, never re-forks.
            assertEquals(SyncBusinessApplier.Outcome.REPLAY, apply(b, decision))
            assertEquals(1, b.syncHeadDao().heads(vault, "subscription", entityId).size)
        } finally { a.close(); b.close() }
    }

    @Test fun unilateral_keep_local_decision_is_published_and_peer_converges() = runBlocking {
        val a = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(a, "device_a"); configure(b, "device_b")
            val opA = subOp("operation_a_keep_local_01", "device_a", 1, "A")
            val opB = subOp("operation_b_keep_local_02", "device_b", 1, "B")
            a.withTransaction { applier(a).apply(vault, opA); applier(a).apply(vault, opB) }
            b.withTransaction { applier(b).apply(vault, opB); applier(b).apply(vault, opA) }

            assertTrue(resolver(a).resolveKeepLocal(vault, opB.operationId))
            assertEquals("A", a.subscriptionDao().findById(entityId)!!.name)
            val decision = published(a) as BusinessOperation.SubscriptionUpsert
            assertEquals("A", decision.entity.name)
            assertEquals(setOf(opA.operationId, opB.operationId), decision.resolves)

            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(b, decision))
            assertEquals("A", b.subscriptionDao().findById(entityId)!!.name)
            assertEquals(1, b.syncHeadDao().heads(vault, "subscription", entityId).size)
            assertEquals(0, b.syncConflictDao().countForVault(vault))
        } finally { a.close(); b.close() }
    }

    @Test fun payment_period_conflict_keep_remote_propagates_with_different_payment_ids() = runBlocking {
        val a = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(a, "device_a"); configure(b, "device_b")
            val localPay = PaymentRecordEntity("pay_local_0000000001", "sub_pay_propagation", 100, 1_000, "CNY", "USER_CONFIRMED", 1)
            val remotePay = PaymentRecordEntity("pay_remote_000000002", "sub_pay_propagation", 100, 2_000, "CNY", "USER_CONFIRMED", 1)
            a.paymentRecordDao().insert(localPay)
            b.paymentRecordDao().insert(remotePay)
            val op = BusinessOperation.PaymentUpsert("operation_pay_conflict_01", "device_b", mapOf("device_b" to 1L), false, remotePay)
            assertEquals(SyncBusinessApplier.Outcome.CONFLICT, apply(a, op))
            assertEquals(1, a.syncConflictDao().countForVault(vault))

            assertTrue(resolver(a).resolveKeepRemote(vault, op.operationId))
            val rowsA = a.paymentRecordDao().bySubscription("sub_pay_propagation")
            assertEquals(1, rowsA.size)
            assertEquals(2_000, rowsA.single().amountMinor)
            assertEquals("pay_remote_000000002", rowsA.single().id)

            val decision = published(a) as BusinessOperation.PaymentUpsert
            assertEquals("pay_remote_000000002", decision.entity.id)
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(b, decision))
            val rowsB = b.paymentRecordDao().bySubscription("sub_pay_propagation")
            assertEquals(1, rowsB.size)
            assertEquals(2_000, rowsB.single().amountMinor)
            assertEquals(1, b.syncHeadDao().heads(vault, "payment", op.entityId).size)
        } finally { a.close(); b.close() }
    }

    @Test fun payment_period_conflict_keep_local_publishes_the_local_period_row() = runBlocking {
        val a = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(a, "device_a"); configure(b, "device_b")
            val localPay = PaymentRecordEntity("pay_local_0000000001", "sub_pay_local", 100, 1_000, "CNY", "USER_CONFIRMED", 1)
            val remotePay = PaymentRecordEntity("pay_remote_000000002", "sub_pay_local", 100, 2_000, "CNY", "USER_CONFIRMED", 1)
            a.paymentRecordDao().insert(localPay)
            b.paymentRecordDao().insert(remotePay)
            val op = BusinessOperation.PaymentUpsert("operation_pay_conflict_02", "device_b", mapOf("device_b" to 1L), false, remotePay)
            assertEquals(SyncBusinessApplier.Outcome.CONFLICT, apply(a, op))

            assertTrue(resolver(a).resolveKeepLocal(vault, op.operationId))
            val rowsA = a.paymentRecordDao().bySubscription("sub_pay_local")
            assertEquals(1, rowsA.size)
            assertEquals(1_000, rowsA.single().amountMinor)
            val decision = published(a) as BusinessOperation.PaymentUpsert
            assertEquals("pay_local_0000000001", decision.entity.id)

            // Peer replaces its remote period row with the decided local row (different payment id, same period).
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(b, decision))
            val rowsB = b.paymentRecordDao().bySubscription("sub_pay_local")
            assertEquals(1, rowsB.size)
            assertEquals(1_000, rowsB.single().amountMinor)
            assertEquals("pay_local_0000000001", rowsB.single().id)
        } finally { a.close(); b.close() }
    }

    @Test fun category_conflict_resolution_updates_peer_row() = runBlocking {
        val a = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(a, "device_a"); configure(b, "device_b")
            val base = CategoryEntity("cat_resolution_0001", "Old", "play", 1, false, 1)
            val opA = BusinessOperation.CategoryUpsert("operation_cat_a_000001", "device_a", mapOf("device_a" to 1L), false, base)
            val opB = BusinessOperation.CategoryUpsert("operation_cat_b_000002", "device_b", mapOf("device_b" to 1L), false, base.copy(name = "Remote"))
            a.withTransaction { applier(a).apply(vault, opA); applier(a).apply(vault, opB) }
            b.withTransaction { applier(b).apply(vault, opB); applier(b).apply(vault, opA) }
            assertEquals(1, a.syncConflictDao().countForVault(vault))

            assertTrue(resolver(a).resolveKeepRemote(vault, opB.operationId))
            assertEquals("Remote", a.categoryDao().findById(base.id)!!.name)
            val decision = published(a) as BusinessOperation.CategoryUpsert
            assertEquals("Remote", decision.entity.name)
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(b, decision))
            assertEquals("Remote", b.categoryDao().findById(base.id)!!.name)
            assertEquals(1, b.syncHeadDao().heads(vault, "category", base.id).size)
        } finally { a.close(); b.close() }
    }

    @Test fun deletion_conflict_resolution_propagates_both_ways() = runBlocking {
        val a = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(a, "device_a"); configure(b, "device_b")
            val tombstone = subOp("operation_del_conflict_01", "device_a", 1, "DELETED", deleted = true, deletedAt = 5L)
            val edit = subOp("operation_edit_conflict_02", "device_b", 1, "EDITED")
            a.withTransaction { applier(a).apply(vault, tombstone); applier(a).apply(vault, edit) }
            b.withTransaction { applier(b).apply(vault, tombstone); applier(b).apply(vault, edit) }
            assertEquals(1, a.syncConflictDao().countForVault(vault))

            // Keep the edit → the decision explicitly resurrects the deleted subscription on the peer as well.
            assertTrue(resolver(a).resolveKeepRemote(vault, edit.operationId))
            assertNull(a.subscriptionDao().findById(entityId)!!.deletedAtUtcMillis)
            val resurrect = published(a) as BusinessOperation.SubscriptionUpsert
            assertFalse(resurrect.deleted)
            assertTrue(resurrect.resolves.contains(tombstone.operationId))
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(b, resurrect))
            assertNull("peer must converge on the restored row", b.subscriptionDao().findById(entityId)!!.deletedAtUtcMillis)
        } finally { a.close(); b.close() }
    }

    @Test fun late_third_device_converges_when_resolution_arrives_before_the_original_versions() = runBlocking {
        val a = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val c = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            configure(a, "device_a"); configure(c, "device_c")
            val opA = subOp("operation_a_late_00000001", "device_a", 1, "A")
            val opB = subOp("operation_b_late_00000002", "device_b", 1, "B")
            a.withTransaction { applier(a).apply(vault, opA); applier(a).apply(vault, opB) }
            assertTrue(resolver(a).resolveKeepRemote(vault, opB.operationId))
            val decision = published(a)

            // Third device receives the decision first, then the older versions.
            assertEquals(SyncBusinessApplier.Outcome.APPLIED, apply(c, decision))
            assertEquals(SyncBusinessApplier.Outcome.REPLAY, apply(c, opA))
            assertEquals(SyncBusinessApplier.Outcome.REPLAY, apply(c, opB))
            assertEquals("B", c.subscriptionDao().findById(entityId)!!.name)
            assertEquals(1, c.syncHeadDao().heads(vault, "subscription", entityId).size)
            assertEquals(0, c.syncConflictDao().countForVault(vault))
        } finally { a.close(); c.close() }
    }
}
