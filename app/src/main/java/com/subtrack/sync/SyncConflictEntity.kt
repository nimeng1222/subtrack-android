package com.subtrack.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * A remote business operation that was NOT applied because it is causally CONCURRENT with a local head. We keep its
 * plaintext payload (local-only, same sensitivity as the ledger) so a user can later resolve the conflict by keeping
 * the remote version. Without this, "keep remote" would be impossible after the download checkpoint advances.
 */
@Entity(
    tableName = "sync_conflict",
    primaryKeys = ["vaultId", "operationId"],
    indices = [Index(value = ["vaultId", "entityType", "entityId"])],
)
data class SyncConflictEntity(
    val vaultId: String,
    val operationId: String,
    val entityType: String,
    val entityId: String,
    val payload: ByteArray,
)

@Dao
interface SyncConflictDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncConflictEntity)

    @Query("SELECT * FROM sync_conflict WHERE vaultId = :vaultId AND operationId = :operationId")
    suspend fun find(vaultId: String, operationId: String): SyncConflictEntity?

    @Query("SELECT * FROM sync_conflict WHERE vaultId = :vaultId")
    suspend fun listForVault(vaultId: String): List<SyncConflictEntity>

    @Query("DELETE FROM sync_conflict WHERE vaultId = :vaultId AND operationId = :operationId")
    suspend fun deleteOne(vaultId: String, operationId: String): Int

    @Query("DELETE FROM sync_conflict WHERE vaultId = :vaultId AND entityType = :entityType AND entityId = :entityId")
    suspend fun deleteEntity(vaultId: String, entityType: String, entityId: String): Int

    @Query("SELECT COUNT(*) FROM sync_conflict WHERE vaultId = :vaultId")
    suspend fun countForVault(vaultId: String): Int
}
