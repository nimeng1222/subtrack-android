package com.subtrack

import androidx.room.Room
import androidx.room.withTransaction
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** G/O2 audit gaps: partial publish retry must not duplicate; cross-currency payment conflict; cross-tenant page rejected. */
class SyncAuditGapsDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java)
        .allowMainThreadQueries().build()
    private val keyProvider: suspend (Int) -> ByteArray = { _: Int -> ByteArray(32) { 9 } }

    @Test fun retrying_a_partially_applied_batch_does_not_duplicate_operations() = runBlocking {
        val db = db()
        try {
            val vault = "vault_o2_retry"
            db.syncDao().putState(SyncStateEntity(vault, "device_a", 1, 0, enabled = true))
            val engine = SyncEngine(db, SyncWire("http://127.0.0.1:18450", com.subtrack.network.NetworkTransport(debug = true)))
            val heads = SyncHeadStore(db.syncHeadDao())
            suspend fun op(id: String, stamp: Long) = BusinessOperation.PaymentUpsert(
                "op_dbg_" + id.padEnd(34, '0'), "device_a", heads.nextClock(vault, "payment", id, "device_a"), false,
                PaymentRecordEntity(id, "sub_x", 100, 1000, "CNY", "USER_CONFIRMED", stamp),
            )
            val first = op("pay_a", 10L)
            engine.enqueueBusiness(vault, first, keyProvider) { heads.integrate(vault, "payment", "pay_a", CausalVersion(first.operationId, first.clock, false)) }
            // Fail the SECOND insert as if the process died mid-batch.
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER inject_fail BEFORE INSERT ON sync_outbox " +
                    "WHEN NEW.operationId LIKE 'op_dbg_%' AND (SELECT COUNT(*) FROM sync_outbox WHERE operationId LIKE 'op_dbg_%') >= 1 " +
                    "BEGIN SELECT RAISE(ABORT, 'injected'); END;",
            )
            val second = op("pay_b", 20L)
            val error = runCatching {
                engine.enqueueBusiness(vault, second, keyProvider) { heads.integrate(vault, "payment", "pay_b", CausalVersion(second.operationId, second.clock, false)) }
            }.exceptionOrNull()
            assertNotNull("the injected second insert must fail", error)
            assertEquals("only the first op may be queued", 1, db.syncDao().pending(vault, 100).size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER inject_fail")
            // Retry the same deterministic ids: no duplicate rows, both ops queued exactly once.
            for (o in listOf(first, second)) {
                if (db.syncDao().outboxHas(o.operationId) == 0) {
                    engine.enqueueBusiness(vault, o, keyProvider) { heads.integrate(vault, "payment", o.entityId, CausalVersion(o.operationId, o.clock, false)) }
                }
            }
            val pending = db.syncDao().pending(vault, 100)
            assertEquals(2, pending.size)
            assertEquals("operation ids must be unique", pending.size, pending.map { it.operationId }.distinct().size)
        } finally { db.close() }
    }

    @Test fun same_period_different_currency_payment_is_an_explicit_conflict() = runBlocking {
        val db = db()
        try {
            val vault = "vault_pay_currency"
            db.syncDao().putState(SyncStateEntity(vault, "device_a", 1, 0, enabled = true))
            val applier = SyncBusinessApplier(
                db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()),
                { 0L }, db.syncConflictDao(),
            )
            fun op(oId: String, dev: String, minor: Long, currency: String) = BusinessOperation.PaymentUpsert(
                oId, dev, mapOf(dev to 1L), false,
                PaymentRecordEntity("pay_ccy", "sub_ccy", 200, minor, currency, "USER_CONFIRMED", 1L),
            )
            db.withTransaction { applier.apply(vault, op("op_ccy_a_00000001", "device_a", 1000L, "CNY")) }
            val outcome = db.withTransaction { applier.apply(vault, op("op_ccy_b_00000001", "device_b", 900L, "USD")) }
            assertEquals("same period with a different currency/amount must conflict", SyncBusinessApplier.Outcome.CONFLICT, outcome)
            val row = db.paymentRecordDao().findById("pay_ccy")!!
            assertEquals("the local payment row must not be overwritten", 1000L, row.amountMinor)
            assertEquals("CNY", row.currency)
            assertEquals(1, db.syncConflictDao().countForVault(vault))
        } finally { db.close() }
    }

    @Test fun page_encrypted_for_another_tenant_is_rejected_without_advancing_cursor() = runBlocking {
        val db = db()
        try {
            val vaultA = "vault_tenant_a"
            db.syncDao().putState(SyncStateEntity(vaultA, "device_a", 1, 0, enabled = true))
            val foreignKey = ByteArray(32) { 7 }
            val envelope = SyncCrypto.encrypt(foreignKey, "vault_tenant_b", "op_tenant_b_000001", 1, "payload".toByteArray())
            val page = listOf(RemoteOperation(1L, "op_tenant_b_000001", envelope))
            val error = runCatching {
                SyncTransactions(db).applyPage(vaultA, 0L, page, { foreignKey }, apply = { _, _ -> })
            }.exceptionOrNull()
            assertNotNull("a page for another vault must not decrypt/apply", error)
            assertEquals("cursor must not advance", 0L, db.syncDao().state(vaultA)!!.cursor)
            assertEquals("no ledger/receipt side effects", 0, db.syncDao().pending(vaultA, 10).size)
        } finally { db.close() }
    }
}
