package com.subtrack.sync

import com.subtrack.data.db.SubTrackDatabase

/**
 * Read-only status snapshot backing the (future) sync settings surface: device identity, key epoch, download checkpoint
 * ("last synced revision"), number of pending uploads in the outbox and number of unresolved conflicts (entities with
 * more than one causal head). Pure read model — never mutates sync state.
 */
data class SyncStatusSnapshot(
    val vaultId: String,
    val deviceId: String,
    val keyEpoch: Int,
    val cursor: Long,
    val pendingUploads: Int,
    val conflicts: Int,
) {
    val lastSyncedRevision: Long get() = cursor
    val hasConflicts: Boolean get() = conflicts > 0
}

class SyncStatusRepository(private val db: SubTrackDatabase) {
    suspend fun snapshot(vaultId: String): SyncStatusSnapshot? {
        val state = db.syncDao().state(vaultId) ?: return null
        return SyncStatusSnapshot(
            vaultId = vaultId,
            deviceId = state.deviceId,
            keyEpoch = state.keyEpoch,
            cursor = state.cursor,
            pendingUploads = db.syncDao().pendingCount(vaultId),
            conflicts = db.syncHeadDao().conflictEntityCount(vaultId),
        )
    }
}
