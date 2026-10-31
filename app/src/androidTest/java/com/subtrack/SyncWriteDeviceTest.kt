package com.subtrack

import androidx.room.Room
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
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
import java.util.UUID

/**
 * Verifies the repository write path is wired through the transactional outbox: a business write commits the ledger
 * row, its encrypted outbox operation and its persisted causal head in one transaction, and a failure rolls all back.
 * Also proves the outbox ciphertext decrypts back into the same business operation.
 */
class SyncWriteDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_write_test"
    private val device = "device_writer"
    private val today = LocalDate.of(2026, 10, 27)
    private val clock = object : AppClock {
        override fun today() = today
        override fun nowMillis() = 1_700_000_000_000
        override fun reminderTime() = LocalTime.of(9, 0)
    }
    private val recovery = SyncCrypto.generateRecoveryKey()
    private val keyProvider: suspend (Int) -> ByteArray = { SyncCrypto.parseRecoveryKey(recovery) }

    private fun sub() = Subscription(
        id = UUID.randomUUID().toString(), name = "SYNC WRITE", categoryId = "other",
        cycle = BillingCycleType.MONTHLY, anchorDate = today.minusMonths(1), nextChargeDate = today, priceMinor = 1000L,
    )

    private fun gateway(db: SubTrackDatabase): OutboxSyncWriteGateway {
        val engine = SyncEngine(db, SyncWire("https://sync.example.com", NetworkTransport(false)))
        return OutboxSyncWriteGateway(vault, device, SyncHeadStore(db.syncHeadDao()), engine, keyProvider)
    }

    private fun decode(item: SyncOutboxEntity): BusinessOperation {
        val key = SyncCrypto.parseRecoveryKey(recovery)
        return try {
            BusinessCodec.decode(SyncCrypto.decrypt(key, vault, item.operationId,
                SyncCrypto.Envelope(keyEpoch = item.keyEpoch, nonce = item.nonce, ciphertext = item.ciphertext)))
        } finally { key.fill(0) }
    }

    @Test fun repository_write_commits_ledger_outbox_and_head_atomically_and_round_trips() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, enabled = true))
            val repo = SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db, gateway(db))
            val s = sub()
            repo.upsert(s)

            assertNotNull(db.subscriptionDao().findById(s.id))
            val pending = db.syncDao().pending(vault, 10)
            assertEquals(1, pending.size)
            assertEquals(1, db.syncHeadDao().countForVault(vault))
            val head = db.syncHeadDao().heads(vault, "subscription", s.id).single()
            assertFalse(head.deleted)

            val op = decode(pending.single()) as BusinessOperation.SubscriptionUpsert
            assertEquals(s.id, op.entity.id)
            assertEquals(1000L, op.entity.priceMinor)
            assertEquals(head.headId, op.operationId)
            assertEquals(1L, op.clock[device])
        } finally { db.close() }
    }

    @Test fun archive_enqueues_tombstone_and_clock_increases() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, enabled = true))
            val repo = SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db, gateway(db))
            val s = sub()
            repo.upsert(s)
            repo.archive(s.id)

            val pending = db.syncDao().pending(vault, 10)
            assertEquals(2, pending.size)
            val first = decode(pending[0]) as BusinessOperation.SubscriptionUpsert
            val second = decode(pending[1]) as BusinessOperation.SubscriptionUpsert
            assertFalse(first.deleted)
            assertTrue(second.deleted)
            assertNotNull(second.entity.deletedAtUtcMillis)
            assertTrue(second.clock[device]!! > first.clock[device]!!)
            assertNotNull(db.subscriptionDao().findById(s.id)!!.deletedAtUtcMillis)
        } finally { db.close() }
    }

    @Test fun failed_ledger_mutation_rolls_back_outbox_and_head() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.syncDao().putState(SyncStateEntity(vault, device, 1, 0, enabled = true))
            val entity = SubscriptionEntity(
                id = UUID.randomUUID().toString(), name = "ROLLBACK", categoryId = "other",
                billingCycleType = "MONTHLY", anchorDateEpochDay = 1, nextChargeDateEpochDay = 2,
                priceMinor = 1000, currency = "CNY", status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
            )
            val failure = runCatching {
                gateway(db).subscription(entity, deleted = false) { throw IllegalStateException("simulated ledger failure") }
            }
            assertNotNull(failure.exceptionOrNull())
            assertEquals(0, db.syncDao().pending(vault, 10).size)
            assertEquals(0, db.syncHeadDao().countForVault(vault))
            assertNull(db.subscriptionDao().findById(entity.id))
        } finally { db.close() }
    }
}
