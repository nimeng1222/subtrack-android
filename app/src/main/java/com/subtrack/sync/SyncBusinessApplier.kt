package com.subtrack.sync

import com.subtrack.data.db.CategoryDao
import com.subtrack.data.db.PaymentRecordDao
import com.subtrack.data.db.SubscriptionDao

/**
 * Applies a decrypted remote business operation to the local ledger. Runs inside the caller's Room transaction (see
 * `SyncTransactions.applyPage`), which guarantees the ledger change, the received-operation receipt and the download
 * checkpoint commit or roll back together — and it deliberately writes the DAO directly, NEVER through the outbox, so a
 * remote operation cannot be re-uploaded (no echo/回传死循环).
 *
 * Merge policy (NOT last-write-wins):
 *  - CONCURRENT vs any known head → record a durable conflict head + payload, never touch the ledger.
 *  - A newer non-delete edit that is causally after a tombstone it does not explicitly resolve must NOT resurrect the
 *    ledger (F2). The tombstone head is protected by [Causality.integrate]; the ledger stays deleted and the payload is
 *    surfaced as a conflict so the user can explicitly restore it.
 *  - An operation with a non-empty [BusinessOperation.resolves] set is an explicit user decision (F1): it causally covers
 *    the cited versions, replaces them, and its entity is applied to the ledger (idempotently).
 *  - Older/already-seen operations are ignored (REPLAY).
 */
class SyncBusinessApplier(
    private val subscriptionDao: SubscriptionDao,
    private val paymentDao: PaymentRecordDao,
    private val categoryDao: CategoryDao,
    private val heads: SyncHeadStore,
    private val clockMillis: () -> Long,
    /** Optional: when present, the un-applied payload is persisted so the user can later choose the remote side. */
    private val conflicts: SyncConflictDao? = null,
) {
    enum class Outcome { APPLIED, REPLAY, CONFLICT }

    suspend fun apply(vaultId: String, operation: BusinessOperation): Outcome {
        val type = operation.kind
        val entityId = operation.entityId
        val current = heads.heads(vaultId, type, entityId)
        val incoming = CausalVersion(operation.operationId, operation.clock, operation.deleted)

        if (current.any { it == incoming }) return Outcome.REPLAY
        val relations = current.map { Causality.compare(it.clock, incoming.clock) }
        // Same clock with a different id is an invalid/ambiguous operation → ignore rather than overwrite.
        if (relations.any { it == CausalRelation.EQUAL }) return Outcome.REPLAY

        val explicitResolution = operation.resolves.isNotEmpty()

        if (!explicitResolution) {
            if (relations.any { it == CausalRelation.CONCURRENT }) {
                heads.integrate(vaultId, type, entityId, incoming)
                recordConflict(vaultId, operation)
                return Outcome.CONFLICT
            }
            if (relations.any { it == CausalRelation.AFTER }) return Outcome.REPLAY
            // A newer edit must not silently resurrect a tombstone it does not explicitly resolve (F2).
            val protectedTombstone = !operation.deleted &&
                current.any { it.deleted && Causality.compare(it.clock, incoming.clock) == CausalRelation.BEFORE }
            // Validate the merge before touching the ledger; invalid → throw → roll back.
            Causality.integrate(current, incoming, operation.resolves)
            if (protectedTombstone) {
                heads.integrate(vaultId, type, entityId, incoming)
                recordConflict(vaultId, operation)
                return Outcome.CONFLICT
            }
            applyToLedger(vaultId, operation, explicitResolution = false, incoming)?.let { return it }
            heads.integrate(vaultId, type, entityId, incoming)
            return Outcome.APPLIED
        }

        // G2: an explicit decision may only eliminate the versions it causally covers AND explicitly cites. It must not
        // bypass AFTER/CONCURRENT: an older decision is a replay, and uncited concurrent branches keep their heads and
        // their conflict material instead of being silently overwritten.
        val merged = Causality.integrate(current, incoming, operation.resolves)
        if (merged.none { it.id == incoming.id }) return Outcome.REPLAY   // older than current heads
        if (merged.size > 1) {
            heads.replace(vaultId, type, entityId, merged)
            recordConflict(vaultId, operation)
            return Outcome.CONFLICT
        }
        applyToLedger(vaultId, operation, explicitResolution = true, incoming)?.let { return it }
        heads.replace(vaultId, type, entityId, merged)
        conflicts?.deleteEntity(vaultId, type, entityId)
        return Outcome.APPLIED
    }

    /** Returns a non-null [Outcome] when this operation is fully handled without the generic ledger+head path. */
    private suspend fun applyToLedger(
        vaultId: String,
        operation: BusinessOperation,
        explicitResolution: Boolean,
        incoming: CausalVersion,
    ): Outcome? {
        when (operation) {
            is BusinessOperation.SubscriptionUpsert -> {
                val ts = operation.entity.deletedAtUtcMillis ?: clockMillis()
                if (operation.deleted) {
                    // A tombstone for an entity this device has never seen must still create the ledger tombstone (F2),
                    // otherwise a later export / first sync would silently lose the deletion.
                    if (subscriptionDao.findById(operation.entity.id) == null) {
                        subscriptionDao.upsert(operation.entity.copy(deletedAtUtcMillis = ts))
                    } else {
                        subscriptionDao.softDelete(operation.entity.id, ts)
                    }
                } else {
                    subscriptionDao.upsert(operation.entity)
                }
                return null
            }

            is BusinessOperation.PaymentUpsert -> {
                if (operation.deleted) return null // payments are additive records; a tombstone has no ledger effect
                val existing = paymentDao.findByPeriod(operation.entity.subscriptionId, operation.entity.paidAtEpochDay)
                if (!explicitResolution && existing != null) {
                    heads.integrate(vaultId, operation.kind, operation.entityId, incoming)
                    val same = existing.amountMinor == operation.entity.amountMinor &&
                        existing.currency == operation.entity.currency
                    if (same) return Outcome.APPLIED // same period + same amount → idempotent, no duplicate row
                    // same period but different amount/currency → explicit conflict, keep local, never silently drop
                    recordConflict(vaultId, operation)
                    return Outcome.CONFLICT
                }
                if (existing != null) {
                    // An explicit resolution (or a different payment id for the same period) replaces the local period
                    // row; otherwise the unique index would silently IGNORE the incoming amount.
                    paymentDao.deleteByPeriod(operation.entity.subscriptionId, operation.entity.paidAtEpochDay)
                }
                paymentDao.insert(operation.entity)
                return null
            }

            is BusinessOperation.CategoryUpsert -> {
                // F3: remote category edits must actually update existing rows (seed inserts stay IGNORE-only).
                if (!operation.deleted) categoryDao.upsert(operation.entity)
                return null
            }
        }
    }

    private suspend fun recordConflict(vaultId: String, operation: BusinessOperation) {
        conflicts?.insert(
            SyncConflictEntity(vaultId, operation.operationId, operation.kind, operation.entityId, BusinessCodec.encode(operation)),
        )
    }
}
