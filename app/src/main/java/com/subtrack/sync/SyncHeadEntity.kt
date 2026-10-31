package com.subtrack.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Persisted causal heads for one business entity. A record may have more than one concurrent head
 * ([Causality.integrate] keeps them separate) so conflicts survive process death instead of living only in memory.
 * The vector clock is serialized as `device:counter;...` (device ids cannot contain ':' or ';').
 */
@Entity(
    tableName = "sync_entity_head",
    primaryKeys = ["vaultId", "entityType", "entityId", "headId"],
    indices = [Index(value = ["vaultId", "entityType", "entityId"])],
)
data class SyncHeadEntity(
    val vaultId: String,
    val entityType: String,
    val entityId: String,
    val headId: String,
    val clockCsv: String,
    val deleted: Boolean,
)

@Dao
interface SyncHeadDao {
    @Query("SELECT * FROM sync_entity_head WHERE vaultId = :vaultId AND entityType = :entityType AND entityId = :entityId")
    suspend fun heads(vaultId: String, entityType: String, entityId: String): List<SyncHeadEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: SyncHeadEntity)

    @Query("DELETE FROM sync_entity_head WHERE vaultId = :vaultId AND entityType = :entityType AND entityId = :entityId")
    suspend fun clear(vaultId: String, entityType: String, entityId: String): Int

    @Query("SELECT COUNT(*) FROM sync_entity_head WHERE vaultId = :vaultId")
    suspend fun countForVault(vaultId: String): Int

    /** Number of entities with more than one causal head == unresolved conflicts. */
    @Query("SELECT COUNT(*) FROM (SELECT entityType, entityId FROM sync_entity_head WHERE vaultId = :vaultId GROUP BY entityType, entityId HAVING COUNT(*) > 1)")
    suspend fun conflictEntityCount(vaultId: String): Int

    @Query("DELETE FROM sync_entity_head WHERE vaultId = :vaultId AND entityType = :entityType AND entityId = :entityId AND headId = :headId")
    suspend fun deleteHead(vaultId: String, entityType: String, entityId: String, headId: String): Int
}
