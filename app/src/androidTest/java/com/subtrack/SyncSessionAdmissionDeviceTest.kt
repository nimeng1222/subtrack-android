package com.subtrack

import androidx.room.Room
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.network.NetworkTransport
import com.subtrack.sync.BusinessOperation
import com.subtrack.sync.SyncEngine
import com.subtrack.sync.SyncSessionLostException
import com.subtrack.sync.SyncStateEntity
import com.subtrack.sync.SyncWire
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** M1/M2: the REAL production call chain (engine enqueue + coordinator syncOnce + rotation allocation) honours session
 *  admission and completes an interrupted rotation without replacing the committed key. */
class SyncSessionAdmissionDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
    private fun sub(id: String) = SubscriptionEntity(
        id = id, name = "n", categoryId = "other", billingCycleType = "MONTHLY", anchorDateEpochDay = 1,
        nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )
    private fun engine(db: SubTrackDatabase, admission: Boolean) = SyncEngine(
        db, SyncWire("http://127.0.0.1:18450", NetworkTransport(debug = true)), admission = { admission },
    )
    private val keyProvider: suspend (Int) -> ByteArray = { _: Int -> ByteArray(32) { 3 } }

    @Test fun stale_engine_cannot_enqueue_business_ops() = runBlocking {
        val db = db()
        try {
            db.syncDao().putState(SyncStateEntity("vault_m1_enqueue", "device_a", 1, 0, enabled = true))
            val op = BusinessOperation.SubscriptionUpsert("op_m1_enqueue_00001", "device_a", mapOf("device_a" to 1L), false, sub("sub_m1_0001"))
            val error = runCatching { engine(db, admission = false).enqueueBusiness("vault_m1_enqueue", op, keyProvider) { } }.exceptionOrNull()
            assertTrue("stale business enqueue must be rejected: $error", error is SyncSessionLostException)
            assertEquals(0, db.syncDao().pending("vault_m1_enqueue", 10).size)
        } finally { db.close() }
    }

    @Test fun stale_engine_coordinator_refuses_before_any_network_or_cursor_change() = runBlocking {
        val db = db()
        try {
            db.syncDao().putState(SyncStateEntity("vault_m1_sync", "device_a", 1, 0, enabled = true))
            val error = runCatching {
                engine(db, admission = false).coordinator(keyProvider) { _, _ -> }
                    .syncOnce("vault_m1_sync", "token_m1_token_m1_token_m1_token")
            }.exceptionOrNull()
            assertTrue("coordinator must check admission, got $error", error is SyncSessionLostException)
            assertEquals("cursor must not advance", 0, db.syncDao().state("vault_m1_sync")!!.cursor)
        } finally { db.close() }
    }

    @Test fun interrupted_rotation_adopts_the_committed_key_instead_of_replacing_it() = runBlocking {
        val service = (context.applicationContext as SubTrackApp).container.syncService
        val vault = "vault_m2_recover_" + System.nanoTime()
        // The service reads the real app database, so the state row must live there too.
        com.subtrack.data.db.SubTrackDatabase.get(context).syncDao()
            .putState(SyncStateEntity(vault, "device_a", 1, 0, enabled = true))
        val committed = com.subtrack.sync.SyncCrypto.generateRecoveryKey()
        assertTrue(service.storeKeyChecked(vault, 2, committed))   // key committed, state still epoch 1 (crash window)
        val (epoch, material) = service.allocateNextEpochKey(vault)
        assertEquals(2, epoch)
        assertEquals("the committed key must be adopted, not replaced", committed, material)
        assertEquals(2, com.subtrack.data.db.SubTrackDatabase.get(context).syncDao().state(vault)!!.keyEpoch)
        assertArrayEquals(com.subtrack.sync.SyncCrypto.parseRecoveryKey(committed),
            com.subtrack.sync.SyncKeyStore(context).load(vault, 2))
    }
}
