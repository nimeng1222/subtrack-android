package com.subtrack.sync

/**
 * Reads/writes the persisted causal heads for one entity. All methods that mutate MUST be called inside an existing
 * Room transaction opened by the caller (the same transaction that stores the ledger row + outbox row), so a business
 * write, its encrypted outbox entry, and its causal head commit or roll back together.
 */
class SyncHeadStore(private val dao: SyncHeadDao) {

    suspend fun heads(vaultId: String, entityType: String, entityId: String): List<CausalVersion> =
        dao.heads(vaultId, entityType, entityId).map {
            CausalVersion(it.headId, decodeClock(it.clockCsv), it.deleted)
        }

    /** Next causal clock for a new local edit of this entity (bumps this device's counter above every known head). */
    suspend fun nextClock(vaultId: String, entityType: String, entityId: String, deviceId: String): Map<String, Long> =
        Causality.nextClock(heads(vaultId, entityType, entityId), deviceId)

    /**
     * Merge [incoming] against the persisted heads and persist the resulting head set. Returns the merged heads.
     * Caller must own the surrounding transaction.
     */
    suspend fun integrate(
        vaultId: String,
        entityType: String,
        entityId: String,
        incoming: CausalVersion,
        resolves: Set<String> = emptySet(),
    ): List<CausalVersion> {
        val current = heads(vaultId, entityType, entityId)
        val merged = Causality.integrate(current, incoming, resolves)
        dao.clear(vaultId, entityType, entityId)
        for (head in merged) dao.insert(encode(vaultId, entityType, entityId, head))
        return merged
    }

    /** Persists an already-merged head set (used when an explicit resolution legitimately leaves concurrent heads alive). */
    suspend fun replace(vaultId: String, entityType: String, entityId: String, merged: List<CausalVersion>) {
        require(merged.map { it.id }.distinct().size == merged.size)
        dao.clear(vaultId, entityType, entityId)
        for (head in merged) dao.insert(encode(vaultId, entityType, entityId, head))
    }

    private fun encode(vaultId: String, entityType: String, entityId: String, head: CausalVersion) = SyncHeadEntity(
        vaultId = vaultId,
        entityType = entityType,
        entityId = entityId,
        headId = head.id,
        clockCsv = head.clock.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value}" },
        deleted = head.deleted,
    )

    private fun decodeClock(csv: String): Map<String, Long> =
        if (csv.isBlank()) emptyMap() else csv.split(";").associate { part ->
            val idx = part.lastIndexOf(':')
            require(idx > 0) { "Invalid persisted clock" }
            part.substring(0, idx) to part.substring(idx + 1).toLong()
        }
}
