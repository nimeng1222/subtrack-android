package com.subtrack.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_state WHERE vaultId = :vaultId")
    suspend fun state(vaultId: String): SyncStateEntity?

    @Upsert suspend fun putState(state: SyncStateEntity)

    /** A/K1: atomic, monotonic updates. Each writer touches exactly its own column, so concurrent import/rotation/
     *  checkpoint/disable can never overwrite another writer's epoch, cursor or enabled flag with a stale snapshot. */
    @Query("UPDATE sync_state SET keyEpoch = :epoch WHERE vaultId = :vaultId AND keyEpoch < :epoch")
    suspend fun advanceEpoch(vaultId: String, epoch: Int): Int

    @Query("UPDATE sync_state SET cursor = :cursor WHERE vaultId = :vaultId AND cursor < :cursor")
    suspend fun advanceCursor(vaultId: String, cursor: Long): Int

    @Query("UPDATE sync_state SET enabled = :enabled WHERE vaultId = :vaultId")
    suspend fun setEnabled(vaultId: String, enabled: Boolean): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOutbox(item: SyncOutboxEntity)

    /** O2: retry detection — a deterministic operation id that is already queued must not be enqueued twice. */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE operationId = :operationId")
    suspend fun outboxHas(operationId: String): Int
    @Query("SELECT * FROM sync_outbox WHERE vaultId = :vaultId ORDER BY createdOrder, operationId LIMIT :limit")
    suspend fun pending(vaultId: String, limit: Int): List<SyncOutboxEntity>
    @Query("SELECT COALESCE(MAX(createdOrder), 0) FROM sync_outbox WHERE vaultId = :vaultId")
    suspend fun lastOrder(vaultId: String): Long
    @Query("DELETE FROM sync_outbox WHERE vaultId = :vaultId AND operationId = :operationId")
    suspend fun acknowledge(vaultId: String, operationId: String): Int
    @Query("SELECT * FROM sync_received WHERE vaultId = :vaultId AND operationId = :operationId")
    suspend fun received(vaultId: String, operationId: String): SyncReceivedEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReceived(item: SyncReceivedEntity)

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE vaultId = :vaultId")
    suspend fun pendingCount(vaultId: String): Int
}
