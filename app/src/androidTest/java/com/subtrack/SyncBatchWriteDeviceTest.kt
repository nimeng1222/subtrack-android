package com.subtrack

import androidx.room.Room
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.repository.SubscriptionRepositoryImpl
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.repository.PaymentRecord
import com.subtrack.network.NetworkTransport
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * N4 write-path closure: the renewal/notification-confirmation and import style batch (subscription advance + multiple
 * payments in ONE transaction) enqueues one subscription op + one op per payment, all in the same Room transaction as
 * the ledger writes — committed atomically, or rolled back entirely. Proves notification confirmation/import writes now
 * reach the outbox instead of bypassing SyncTransactions.
 */
class SyncBatchWriteDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_batch_test"
    private val device = "device_batch"
    private val today = LocalDate.of(2026, 10, 27)
    private val clock = object : AppClock {
        override fun today() = today
        override fun nowMillis() = 1_700_000_000_000
        override fun reminderTime() = LocalTime.of(9, 0)
    }
    private val recovery = SyncCrypto.generateRecoveryKey()
    private val keyProvider: suspend (Int) -> ByteArray = { SyncCrypto.parseRecoveryKey(recovery) }

    private fun sub(id: String = "sub_batch_000000001") = Subscription(
        id = id, name = "批量续费", categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = today.minusMonths(1), nextChargeDate = today, priceMinor = 1000L,
    )
    private fun pay(id: String, subId: String, day: Long) = PaymentRecord(id, subId, today.plusDays(day), 1000L, "CNY", null)

    private fun repo(db: SubTrackDatabase): SubscriptionRepositoryImpl {
        val engine = SyncEngine(db, SyncWire("https://sync.example.com", NetworkTransport(false)))
        val gw = OutboxSyncWriteGateway(vault, device, SyncHeadStore(db.syncHeadDao()), engine, keyProvider)
        return SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db, gw)
    }
    private fun decode(item: SyncOutboxEntity): BusinessOperation {
        val key = SyncCrypto.parseRecoveryKey(recovery)
        return try {
            BusinessCodec.decode(SyncCrypto.decrypt(key, vault, item.operationId,
                SyncCrypto.Envelope(keyEpoch = item.keyEpoch, nonce = item.nonce, ciphertext = item.ciphertext)))
        } finally { key.fill(0) }
    }

    @Test fun renewal_batch_commits_ledger_and_all_outbox_ops_atomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, enabled = true))
            val repo = repo(db)
            val s = sub()
            repo.runInTransaction {
                repo.recordPayments(listOf(pay("pay_batch_000000001", s.id, 0), pay("pay_batch_000000002", s.id, 1)))
                repo.upsert(s.copy(nextChargeDate = today.plusMonths(1)))
            }
            assertEquals(2, db.paymentRecordDao().all().size)
            val stored = db.subscriptionDao().findById(s.id)!!
            assertEquals(today.plusMonths(1).toEpochDay().toInt(), stored.nextChargeDateEpochDay)

            val outbox = db.syncDao().pending(vault, 10)
            assertEquals(3, outbox.size)   // 2 payment ops + 1 subscription op
            val kinds = outbox.map { decode(it).kind }
            assertEquals(2, kinds.count { it == "payment" })
            assertEquals(1, kinds.count { it == "subscription" })
        } finally { db.close() }
    }

    @Test fun failed_batch_rolls_back_ledger_outbox_and_heads_together() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, enabled = true))
            val repo = repo(db)
            val s = sub("sub_batch_rollback01")
            val failure = runCatching {
                repo.runInTransaction {
                    repo.recordPayments(listOf(pay("pay_batch_rollback01", s.id, 0)))
                    repo.upsert(s)
                    throw IllegalStateException("simulated failure after writes")
                }
            }
            assertNotNull(failure.exceptionOrNull())
            assertEquals(0, db.paymentRecordDao().all().size)
            assertNull(db.subscriptionDao().findById(s.id))
            assertEquals(0, db.syncDao().pending(vault, 10).size)
            assertEquals(0, db.syncHeadDao().countForVault(vault))
        } finally { db.close() }
    }
}
