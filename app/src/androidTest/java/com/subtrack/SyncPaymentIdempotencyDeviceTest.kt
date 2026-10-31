package com.subtrack

import androidx.room.Room
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * N4 cross-device renewal: the same subscription period confirmed on two devices must yield exactly one real payment
 * (idempotent), while a same-period payment with a DIFFERENT amount/currency must surface an explicit conflict instead
 * of being silently dropped by the unique index.
 */
class SyncPaymentIdempotencyDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_pay_test"
    private val remote = "device_remote"
    private val subId = "sub_payperiod0001"

    private fun pay(id: String, day: Int, amount: Long, currency: String = "CNY") = PaymentRecordEntity(
        id = id, subscriptionId = subId, paidAtEpochDay = day, amountMinor = amount, currency = currency,
        source = "USER_CONFIRMED", createdAtUtcMillis = 1, note = null,
    )
    private fun remoteOp(opId: String, entity: PaymentRecordEntity) =
        BusinessOperation.PaymentUpsert(opId, remote, mapOf(remote to 1L), false, entity)
    private fun applier(db: SubTrackDatabase) = SyncBusinessApplier(
        db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()), { 9L }, db.syncConflictDao(),
    )

    @Test fun same_period_same_amount_is_idempotent_and_different_amount_is_explicit_conflict() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.paymentRecordDao().insert(pay("pay_local", 100, 1_000))                 // existing local payment for period 100
            val applier = applier(db)

            // Same period + same amount from another device → idempotent, no duplicate row.
            assertEquals(
                SyncBusinessApplier.Outcome.APPLIED,
                applier.apply(vault, remoteOp("operation_pay00000001", pay("pay_remote_same", 100, 1_000))),
            )
            assertEquals(1, db.paymentRecordDao().all().size)

            // Same period but different amount → explicit conflict, keep local, do not overwrite/drop silently.
            assertEquals(
                SyncBusinessApplier.Outcome.CONFLICT,
                applier.apply(vault, remoteOp("operation_pay00000002", pay("pay_remote_diff", 100, 2_000))),
            )
            assertEquals(1, db.paymentRecordDao().all().size)
            assertEquals(1_000L, db.paymentRecordDao().findByPeriod(subId, 100)!!.amountMinor)
            assertEquals(1, db.syncConflictDao().countForVault(vault))
        } finally { db.close() }
    }

    /**
     * Regression for a real "keep remote" defect: because the period row already existed locally, the remote insert was
     * dropped by the unique index (subscriptionId, paidAtEpochDay) with OnConflictStrategy.IGNORE, so resolving the
     * conflict reported success while the ledger still held the LOCAL amount. Keep-remote must actually replace the
     * period row and leave exactly one row for that period.
     */
    @Test fun keep_remote_after_payment_conflict_replaces_the_local_period_row() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.paymentRecordDao().insert(pay("pay_local", 100, 1_000))
            val opId = "operation_pay00000003"
            assertEquals(
                SyncBusinessApplier.Outcome.CONFLICT,
                applier(db).apply(vault, remoteOp(opId, pay("pay_remote_diff", 100, 2_000))),
            )
            assertEquals(1, db.syncConflictDao().countForVault(vault))
            assertEquals(1_000L, db.paymentRecordDao().findByPeriod(subId, 100)!!.amountMinor)

            val resolver = SyncConflictRepository(db, db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao())
            assertTrue(resolver.resolveKeepRemote(vault, opId))

            val rows = db.paymentRecordDao().all()
            assertEquals(1, rows.size)                       // replacement, not a second row for the same period
            assertEquals("pay_remote_diff", rows.single().id)
            assertEquals(2_000L, rows.single().amountMinor)  // keep-remote really took effect
            assertEquals(0, db.syncConflictDao().countForVault(vault))
            assertEquals(opId, db.syncHeadDao().heads(vault, "payment", "pay_remote_diff").single().headId)
        } finally { db.close() }
    }
}
