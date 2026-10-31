package com.subtrack.sync

import androidx.room.Entity
import androidx.room.Index

/** Sync metadata is local only and excluded from the portable backup. */
@Entity(tableName = "sync_state", primaryKeys = ["vaultId"])
data class SyncStateEntity(
    val vaultId: String,
    val deviceId: String,
    val keyEpoch: Int,
    val cursor: Long,
    val enabled: Boolean,
)

@Entity(tableName = "sync_outbox", primaryKeys = ["vaultId", "operationId"], indices = [Index(value = ["vaultId", "createdOrder"])])
data class SyncOutboxEntity(
    val vaultId: String,
    val operationId: String,
    val keyEpoch: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val createdOrder: Long,
)

@Entity(tableName = "sync_received", primaryKeys = ["vaultId", "operationId"])
data class SyncReceivedEntity(val vaultId: String, val operationId: String, val digest: String, val serverRevision: Long)
