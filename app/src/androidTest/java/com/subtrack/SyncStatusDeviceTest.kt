package com.subtrack

import androidx.room.Room
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** N4 status read model (pending uploads / conflicts / device / last synced revision) used by the settings surface. */
class SyncStatusDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val vault = "vault_status_test"
    private val device = "device_status"

    private fun item(order: Long) =
        SyncOutboxEntity(vault, "operation_${order.toString().padStart(12, '0')}", 1, ByteArray(12), ByteArray(32), order)

    @Test fun status_reports_pending_uploads_and_unresolved_conflicts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            db.syncDao().putState(SyncStateEntity(vault, device, 1, cursor = 7, enabled = true))
            db.syncDao().insertOutbox(item(1))
            db.syncDao().insertOutbox(item(2))
            val heads = SyncHeadStore(db.syncHeadDao())
            heads.integrate(vault, "subscription", "entityX000000000001", CausalVersion("operation_0000000001", mapOf("devA" to 1L), false))
            heads.integrate(vault, "subscription", "entityX000000000001", CausalVersion("operation_0000000002", mapOf("devB" to 1L), false)) // concurrent
            heads.integrate(vault, "payment", "entityY000000000001", CausalVersion("operation_0000000003", mapOf("devA" to 1L), false))

            val snap = SyncStatusRepository(db).snapshot(vault)!!
            assertEquals(2, snap.pendingUploads)
            assertEquals(1, snap.conflicts)                 // only entityX has >1 head
            assertEquals(7L, snap.cursor)
            assertEquals(7L, snap.lastSyncedRevision)
            assertEquals(device, snap.deviceId)
            assertEquals(1, snap.keyEpoch)
            assertTrue(snap.hasConflicts)
        } finally { db.close() }
    }

    @Test fun missing_vault_returns_null() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try { assertNull(SyncStatusRepository(db).snapshot("no-such-vault")) } finally { db.close() }
    }
}
