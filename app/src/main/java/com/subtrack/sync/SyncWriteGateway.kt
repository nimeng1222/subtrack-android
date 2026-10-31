package com.subtrack.sync

import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubscriptionEntity
import java.util.UUID
import kotlinx.coroutines.sync.withLock

/** Enqueues one business operation atomically with the ledger mutation it describes. */
interface SyncEnqueuePort {
    /**
     * Runs [block] inside the same transactional/serial boundary used for ledger+head+outbox commits (F4). Clock
     * allocation must happen inside this boundary so two concurrent writes cannot read the same head set and then
     * collide on an equal causal clock.
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T

    suspend fun enqueue(
        vaultId: String,
        operation: BusinessOperation,
        keyProvider: suspend (Int) -> ByteArray,
        mutateLedger: suspend () -> Unit,
    )
}

/**
 * Port the repository writes through. The default [DirectSyncWriteGateway] is a pass-through, so existing behaviour and
 * tests are unchanged when sync is not configured. [OutboxSyncWriteGateway] is bound only when a vault is active.
 */
interface SyncWriteGateway {
    suspend fun subscription(entity: SubscriptionEntity, deleted: Boolean, mutate: suspend () -> Unit)
    suspend fun payment(entity: PaymentRecordEntity, mutate: suspend () -> Unit)
    suspend fun category(entity: CategoryEntity, mutate: suspend () -> Unit)
}

/** Pass-through: no sync configured. Business write happens exactly as before. */
object DirectSyncWriteGateway : SyncWriteGateway {
    override suspend fun subscription(entity: SubscriptionEntity, deleted: Boolean, mutate: suspend () -> Unit) = mutate()
    override suspend fun payment(entity: PaymentRecordEntity, mutate: suspend () -> Unit) = mutate()
    override suspend fun category(entity: CategoryEntity, mutate: suspend () -> Unit) = mutate()
}

/**
 * Real gateway: computes the next causal clock from persisted heads, encrypts a [BusinessOperation] and enqueues it in
 * the SAME Room transaction as [mutate] (the actual ledger write) and the head update. A crash therefore never leaves a
 * ledger row without its outbox entry or vice versa; the head update rides along so conflict state survives restarts.
 */
class OutboxSyncWriteGateway(
    private val vaultId: String,
    private val deviceId: String,
    private val heads: SyncHeadStore,
    private val enqueue: SyncEnqueuePort,
    private val keyProvider: suspend (Int) -> ByteArray,
    private val newOperationId: () -> String = { "op_" + UUID.randomUUID().toString().replace("-", "") },
    /** B/G5: admission check evaluated INSIDE the commit transaction; a stale session must not write into the new one. */
    private val admission: suspend () -> Boolean = { true },
    /**
     * S1: true when the caller already holds an outer Room transaction. In that case the vault lock must NOT be acquired
     * again (Room transaction + vault lock would invert the sync lock order and deadlock). Room's own transaction
     * serialization already protects ledger/outbox/head atomicity on this path.
     */
    private val isOwnedTransaction: suspend () -> Boolean = { false },
) : SyncWriteGateway {

    private suspend fun write(
        entityType: String,
        entityId: String,
        deleted: Boolean,
        build: (operationId: String, clock: Map<String, Long>) -> BusinessOperation,
        mutate: suspend () -> Unit,
    ) = if (isOwnedTransaction()) {
        // T1: same outer business transaction (possibly after a dispatcher switch): hop back onto the transaction thread
        // and commit inside the existing Room transaction — never take the vault lock again.
        val owner = requireNotNull(currentTransactionOwner())
        kotlinx.coroutines.withContext(owner.dispatcher) {
            enqueue.inTransaction { writeBody(entityType, entityId, deleted, build, mutate) }
        }
    } else {
        SyncVaultLocks.forVault(vaultId).withLock {
            enqueue.inTransaction { writeBody(entityType, entityId, deleted, build, mutate) }
        }
    }

    private suspend fun writeBody(
        entityType: String,
        entityId: String,
        deleted: Boolean,
        build: (operationId: String, clock: Map<String, Long>) -> BusinessOperation,
        mutate: suspend () -> Unit,
    ) {
        if (!admission()) throw SyncSessionLostException()
        // F4: read heads + allocate the causal clock + write ledger + commit head/outbox in one transaction. Room
        // serializes the outer transaction, so concurrent gateway writes get distinct clocks instead of colliding.
        val clock = heads.nextClock(vaultId, entityType, entityId, deviceId)
        val operationId = newOperationId().also { require(it.matches(Regex("[A-Za-z0-9_-]{16,128}"))) }
        val operation = build(operationId, clock)
        enqueue.enqueue(vaultId, operation, keyProvider) {
            mutate()
            heads.integrate(vaultId, entityType, entityId, CausalVersion(operationId, clock, deleted))
        }
        // R1: invalidation during the body must throw BEFORE the enclosing Room transaction commits.
        if (!admission()) throw SyncSessionLostException()
    }

    override suspend fun subscription(entity: SubscriptionEntity, deleted: Boolean, mutate: suspend () -> Unit) =
        write("subscription", entity.id, deleted, { id, clock -> BusinessOperation.SubscriptionUpsert(id, deviceId, clock, deleted, entity) }, mutate)

    override suspend fun payment(entity: PaymentRecordEntity, mutate: suspend () -> Unit) =
        write("payment", entity.id, false, { id, clock -> BusinessOperation.PaymentUpsert(id, deviceId, clock, false, entity) }, mutate)

    override suspend fun category(entity: CategoryEntity, mutate: suspend () -> Unit) =
        write("category", entity.id, false, { id, clock -> BusinessOperation.CategoryUpsert(id, deviceId, clock, false, entity) }, mutate)
}
