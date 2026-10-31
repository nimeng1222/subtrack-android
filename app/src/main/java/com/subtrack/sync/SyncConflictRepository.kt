package com.subtrack.sync

import androidx.room.withTransaction
import com.subtrack.data.db.CategoryDao
import com.subtrack.data.db.PaymentRecordDao
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionDao
import java.util.UUID

/** Human-readable conflict entry for the resolution surface. */
data class ConflictView(
    val operationId: String,
    val entityType: String,
    val entityId: String,
    val label: String,
)

/**
 * Publishes one user conflict decision as a NEW causally-covering business operation (F1). Production binds this to the
 * encrypted outbox (`SyncEngine.enqueueBusiness`), so a decision made on one device replicates to the others.
 */
fun interface ResolutionPublisher {
    suspend fun publish(vaultId: String, operation: BusinessOperation, mutateLedger: suspend () -> Unit)
}

/**
 * Conflict resolution. A concurrent remote operation that the applier did NOT apply is persisted (see
 * [SyncConflictEntity]); here the user can deterministically resolve it:
 *  - [resolveKeepRemote]: the stored remote entity wins.
 *  - [resolveKeepLocal]: the current local entity wins (dropping the remote side).
 *
 * When a [ResolutionPublisher] is bound (production), the decision itself becomes a new encrypted operation whose causal
 * clock covers every head of that entity and whose `resolves` set cites them, so peers converge after syncing without
 * each choosing manually. Ledger, heads, conflict bookkeeping and the outbox row commit atomically.
 * Without a publisher (legacy/local-only callers) the decision is applied locally only.
 */
class SyncConflictRepository(
    private val db: SubTrackDatabase,
    private val subscriptionDao: SubscriptionDao,
    private val paymentDao: PaymentRecordDao,
    private val categoryDao: CategoryDao,
    private val publisher: ResolutionPublisher? = null,
    private val newOperationId: () -> String = { "op_" + UUID.randomUUID().toString().replace("-", "") },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    /** O4: invoked after a decision is committed (reminder plans must reflect the new ledger immediately). */
    private val afterApply: (suspend () -> Unit)? = null,
) {
    private val conflicts get() = db.syncConflictDao()
    private val heads get() = db.syncHeadDao()
    private val headStore get() = SyncHeadStore(db.syncHeadDao())

    suspend fun conflicts(vaultId: String): List<ConflictView> =
        conflicts.listForVault(vaultId).mapNotNull { row ->
            runCatching { BusinessCodec.decode(row.payload) }.getOrNull()?.let { op ->
                ConflictView(row.operationId, row.entityType, row.entityId, label(op))
            }
        }

    private fun label(op: BusinessOperation): String = when (op) {
        is BusinessOperation.SubscriptionUpsert ->
            "${op.entity.name}（¥未定/金额 ${op.entity.priceMinor} ${op.entity.currency}）" + if (op.deleted) " · 删除" else ""
        is BusinessOperation.PaymentUpsert -> "流水 ${op.entity.amountMinor} ${op.entity.currency}"
        is BusinessOperation.CategoryUpsert -> "分类 ${op.entity.name}"
    }

    suspend fun resolveKeepRemote(vaultId: String, operationId: String): Boolean =
        resolve(vaultId, operationId, keepRemote = true)

    suspend fun resolveKeepLocal(vaultId: String, operationId: String): Boolean =
        resolve(vaultId, operationId, keepRemote = false)

    private suspend fun resolve(vaultId: String, operationId: String, keepRemote: Boolean): Boolean {
        val applied = resolveInternal(vaultId, operationId, keepRemote)
        if (applied) runCatching { afterApply?.invoke() }
        return applied
    }

    private suspend fun resolveInternal(vaultId: String, operationId: String, keepRemote: Boolean): Boolean {
        val row = conflicts.find(vaultId, operationId) ?: return false
        val remote = BusinessCodec.decode(row.payload)
        val type = remote.kind
        val entityId = remote.entityId
        val state = db.syncDao().state(vaultId)
        val currentHeads = headStore.heads(vaultId, type, entityId)
        val local = localOperation(remote)

        // Nothing local to keep → the remote side simply loses; drop its head + conflict (no operation to publish).
        if (!keepRemote && local == null) {
            db.withTransaction {
                heads.deleteHead(vaultId, type, entityId, operationId)
                conflicts.deleteOne(vaultId, operationId)
            }
            return true
        }

        if (publisher == null || state == null || currentHeads.isEmpty()) {
            // Legacy/local-only path (no sync configured, or no persisted state): apply locally, collapse heads.
            db.withTransaction {
                if (keepRemote) applyEntity(remote) else local?.let { applyEntity(it) }
                if (keepRemote) {
                    heads.clear(vaultId, type, entityId)
                    heads.insert(SyncHeadEntity(vaultId, type, entityId, remote.operationId, encodeClock(remote.clock), remote.deleted))
                } else {
                    heads.deleteHead(vaultId, type, entityId, operationId)
                }
                conflicts.deleteEntity(vaultId, type, entityId)
            }
            return true
        }

        val clock = Causality.nextClock(currentHeads, state.deviceId)
        val resolves = currentHeads.map { it.id }.toSet()
        val winner = if (keepRemote) remote else local!!
        val newId = newOperationId().also { require(it.matches(Regex("[A-Za-z0-9_-]{16,128}"))) }
        val resolution = reissue(winner, newId, state.deviceId, clock, resolves)
        publisher.publish(vaultId, resolution) {
            applyEntity(winner)
            headStore.integrate(vaultId, type, entityId, CausalVersion(newId, clock, resolution.deleted), resolves)
            conflicts.deleteEntity(vaultId, type, entityId)
        }
        return true
    }

    /**
     * Current local entity as an operation (placeholder identity is reissued before publishing). Payments are looked up
     * by PERIOD: a same-period conflict may carry a different remote payment id, and "keep local" must publish the row
     * the user is actually keeping.
     */
    private suspend fun localOperation(remote: BusinessOperation): BusinessOperation? = when (remote) {
        is BusinessOperation.SubscriptionUpsert -> subscriptionDao.findById(remote.entityId)?.let { e ->
            BusinessOperation.SubscriptionUpsert(PLACEHOLDER, e.id, mapOf(PLACEHOLDER_DEVICE to 1L), e.deletedAtUtcMillis != null, e)
        }
        is BusinessOperation.PaymentUpsert -> paymentDao.findByPeriod(remote.entity.subscriptionId, remote.entity.paidAtEpochDay)?.let { e ->
            BusinessOperation.PaymentUpsert(PLACEHOLDER, e.id, mapOf(PLACEHOLDER_DEVICE to 1L), false, e)
        }
        is BusinessOperation.CategoryUpsert -> categoryDao.findById(remote.entityId)?.let { e ->
            BusinessOperation.CategoryUpsert(PLACEHOLDER, e.id, mapOf(PLACEHOLDER_DEVICE to 1L), false, e)
        }
    }

    private fun reissue(
        op: BusinessOperation,
        operationId: String,
        deviceId: String,
        clock: Map<String, Long>,
        resolves: Set<String>,
    ): BusinessOperation = when (op) {
        is BusinessOperation.SubscriptionUpsert -> op.copy(operationId = operationId, deviceId = deviceId, clock = clock, resolves = resolves)
        is BusinessOperation.PaymentUpsert -> op.copy(operationId = operationId, deviceId = deviceId, clock = clock, resolves = resolves)
        is BusinessOperation.CategoryUpsert -> op.copy(operationId = operationId, deviceId = deviceId, clock = clock, resolves = resolves)
    }

    /** Apply the winning entity to the ledger (mirrors [SyncBusinessApplier] semantics, including payment period replacement). */
    private suspend fun applyEntity(op: BusinessOperation) {
        when (op) {
            is BusinessOperation.SubscriptionUpsert -> if (op.deleted) {
                val ts = op.entity.deletedAtUtcMillis ?: clockMillis()
                if (subscriptionDao.findById(op.entity.id) == null) subscriptionDao.upsert(op.entity.copy(deletedAtUtcMillis = ts))
                else subscriptionDao.softDelete(op.entity.id, ts)
            } else {
                subscriptionDao.upsert(op.entity)
            }
            is BusinessOperation.PaymentUpsert -> if (!op.deleted) {
                // Replace the local period row so the unique index cannot silently IGNORE the chosen amount.
                paymentDao.deleteByPeriod(op.entity.subscriptionId, op.entity.paidAtEpochDay)
                paymentDao.insert(op.entity)
            }
            is BusinessOperation.CategoryUpsert -> if (!op.deleted) categoryDao.upsert(op.entity)
        }
    }

    private fun encodeClock(clock: Map<String, Long>): String =
        clock.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value}" }

    private companion object {
        const val PLACEHOLDER = "op_localplaceholder0001"
        const val PLACEHOLDER_DEVICE = "local"
    }
}
