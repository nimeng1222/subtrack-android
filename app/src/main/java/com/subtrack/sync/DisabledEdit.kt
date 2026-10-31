package com.subtrack.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import java.util.UUID

/**
 * P1/P3/P4: persistent record of an edit made while sync was disabled. It exists only for disabled-period writes
 * (active-gateway writes and remote applies never insert here), carries the STABLE operation id chosen when the edit was
 * first recorded, and tracks two independent completion markers:
 *  - queuedAt: the outbox insert for [opId] committed (survives upload acknowledgement deleting the outbox row);
 *  - publishedAt: the batch that included this row finished, so it no longer appears in the preview.
 */
@Entity(tableName = "sync_disabled_edit", indices = [androidx.room.Index("vaultId"), androidx.room.Index("opId", unique = true)])
data class DisabledEditEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val vaultId: String,
    val entityType: String,
    val entityId: String,
    val opId: String,
    val createdAtMillis: Long,
    val queuedAt: Long? = null,
    val publishedAt: Long? = null,
    /** Q3: set when the user confirms this row; a newer edit must then get its own row, never mutate this snapshot. */
    val confirmedAt: Long? = null,
)

@Dao
interface DisabledEditDao {
    @androidx.room.Insert suspend fun insert(row: DisabledEditEntity): Long

    @Query("SELECT * FROM sync_disabled_edit WHERE vaultId = :vaultId AND entityType = :type AND entityId = :id AND publishedAt IS NULL")
    suspend fun pendingFor(vaultId: String, type: String, id: String): List<DisabledEditEntity>

    @Query("SELECT * FROM sync_disabled_edit WHERE vaultId = :vaultId AND publishedAt IS NULL ORDER BY seq ASC")
    suspend fun pendingRows(vaultId: String): List<DisabledEditEntity>

    @Query("UPDATE sync_disabled_edit SET queuedAt = :now WHERE seq = :seq AND queuedAt IS NULL")
    suspend fun markQueued(seq: Long, now: Long): Int

    @Query("UPDATE sync_disabled_edit SET publishedAt = :now WHERE vaultId = :vaultId AND entityType = :type AND entityId = :id AND seq <= :upToSeq AND publishedAt IS NULL")
    suspend fun markPublishedThrough(vaultId: String, type: String, id: String, upToSeq: Long, now: Long): Int

    @Query("UPDATE sync_disabled_edit SET confirmedAt = :now WHERE seq = :seq AND publishedAt IS NULL")
    suspend fun markConfirmed(seq: Long, now: Long): Int

    @Query("UPDATE sync_disabled_edit SET confirmedAt = NULL WHERE seq IN (:seqs) AND publishedAt IS NULL")
    suspend fun clearConfirmed(seqs: List<Long>): Int

    @Query("UPDATE sync_disabled_edit SET publishedAt = :now WHERE seq = :seq AND publishedAt IS NULL")
    suspend fun markPublished(seq: Long, now: Long): Int

    @Query("DELETE FROM sync_disabled_edit WHERE vaultId = :vaultId")
    suspend fun clearVault(vaultId: String)
}

/** Chooses the stable operation id for a new disabled-period edit (random, never reused across edits). */
internal fun newDisabledEditOpId(): String = "op_dbg_" + UUID.randomUUID().toString().replace("-", "").take(40)
