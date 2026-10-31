package com.subtrack

import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.sync.SyncStateEntity
import com.subtrack.sync.SyncCrypto
import com.subtrack.sync.SyncProductService
import com.subtrack.sync.SyncRecoveryBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * J1/J2: importing key material is not "restore sync". A same-bundle retry after a crash between key-file commit and the
 * Room state write must COMPLETE the epoch state; it must never re-enable a disabled session or move cursor/enabled.
 */
class SyncImportRecoveryDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val service: SyncProductService get() = (context.applicationContext as SubTrackApp).container.syncService
    private val db: SubTrackDatabase get() = SubTrackDatabase.get(context)

    private fun key() = SyncCrypto.generateRecoveryKey()

    @Test fun retry_after_key_file_commit_completes_epoch_state() = runBlocking {
        val vault = "vault_j1_retry_" + System.nanoTime()
        db.syncDao().putState(SyncStateEntity(vault, "device_a", 1, 42, enabled = true))
        val material = key()
        // First attempt died after committing the KEY FILE, before the Room state write.
        assertEquals(true, service.storeKeyChecked(vault, 2, material))
        assertEquals("state is still at the old epoch", 1, db.syncDao().state(vault)!!.keyEpoch)
        // Retry of the same bundle: key is already there (idempotent) but state must be completed.
        service.storeKeyChecked(vault, 2, material)
        service.reconcileImportedEpoch(vault, 2)
        val state = db.syncDao().state(vault)!!
        assertEquals(2, state.keyEpoch)
        assertEquals("cursor must be preserved", 42, state.cursor)
        assertTrue("enabled flag must be preserved", state.enabled)
        // A further retry is a no-op.
        service.reconcileImportedEpoch(vault, 2)
        assertEquals(2, db.syncDao().state(vault)!!.keyEpoch)
    }

    @Test fun reconcile_never_moves_epoch_backwards() = runBlocking {
        val vault = "vault_j1_back_" + System.nanoTime()
        db.syncDao().putState(SyncStateEntity(vault, "device_a", 3, 7, enabled = true))
        service.reconcileImportedEpoch(vault, 2)
        assertEquals(3, db.syncDao().state(vault)!!.keyEpoch)
    }

    @Test fun import_does_not_reenable_disabled_session_or_change_cursor() = runBlocking {
        val vault = "vault_j2_disabled_" + System.nanoTime()
        db.syncDao().putState(SyncStateEntity(vault, "device_a", 1, 9, enabled = false))
        val material = key()
        val bundle = SyncRecoveryBundle.create(vault, 2, material)
        assertNotNull(bundle)
        service.storeKeyChecked(vault, 2, material)
        service.reconcileImportedEpoch(vault, 2)
        val state = db.syncDao().state(vault)!!
        assertEquals(2, state.keyEpoch)
        assertFalse("a disabled session must stay disabled after import", state.enabled)
        assertEquals("cursor must not change", 9, state.cursor)
    }

    @Test fun different_key_for_existing_epoch_is_rejected_without_state_changes() = runBlocking {
        val vault = "vault_j1_reject_" + System.nanoTime()
        db.syncDao().putState(SyncStateEntity(vault, "device_a", 1, 5, enabled = false))
        service.storeKeyChecked(vault, 1, key())
        val error = runCatching { service.storeKeyChecked(vault, 1, key()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        val state = db.syncDao().state(vault)!!
        assertEquals(1, state.keyEpoch)
        assertEquals(5, state.cursor)
        assertFalse(state.enabled)
    }

    @Test fun concurrent_epoch_checkpoint_and_disable_updates_do_not_lose_each_other() = runBlocking {
        val vault = "vault_k1_concurrent_" + System.nanoTime()
        db.syncDao().putState(SyncStateEntity(vault, "device_a", 1, 0, enabled = true))
        // Interleaved writers: import advancing epoch, download checkpoint advancing cursor, and disable. Each touches only
        // its own column atomically, so none may resurrect a stale snapshot of the others.
        val scope = CoroutineScope(Dispatchers.Default)
        val jobs = listOf(
            scope.async { service.storeKeyChecked(vault, 2, key()); service.reconcileImportedEpoch(vault, 2) },
            scope.async { db.syncDao().advanceCursor(vault, 50) },
            scope.async { db.syncDao().setEnabled(vault, false) },
        )
        jobs.forEach { it.await() }
        scope.cancel()
        val state = db.syncDao().state(vault)!!
        assertEquals(2, state.keyEpoch)
        assertEquals(50, state.cursor)
        assertFalse(state.enabled)
    }

    @Test fun concurrent_rotations_never_return_the_same_epoch_with_different_keys() = runBlocking {
        val vault = "vault_l1_rotate_" + System.nanoTime()
        db.syncDao().putState(SyncStateEntity(vault, "device_a", 1, 0, enabled = true))
        val scope = CoroutineScope(Dispatchers.Default)
        val results = (1..4).map { scope.async { service.allocateNextEpochKey(vault) } }.map { it.await() }
        scope.cancel()
        val epochs = results.map { it.first }
        assertEquals("every rotation must get a distinct epoch", epochs.size, epochs.distinct().size)
        assertEquals("state follows the highest epoch", epochs.max(), db.syncDao().state(vault)!!.keyEpoch)
        for ((epoch, material) in results) {
            assertArrayEquals("the key stored for an epoch must be exactly the one returned",
                com.subtrack.sync.SyncCrypto.parseRecoveryKey(material),
                com.subtrack.sync.SyncKeyStore(context).load(vault, epoch))
        }
    }
}

