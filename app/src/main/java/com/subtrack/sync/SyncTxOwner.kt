package com.subtrack.sync

import androidx.room.withTransaction
import com.subtrack.data.db.SubTrackDatabase
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T1: explicit ownership of the outer business transaction. It travels in the coroutine context (so a `withContext(IO)`
 * inside the block keeps it) and pins the database, the vault and the single transaction thread. Gateway work that belongs
 * to this transaction hops back to that thread instead of taking the vault lock again (which would invert the lock order
 * against sync and deadlock).
 *
 * T2: the coordinator itself consults this element. `runInTransaction` nested inside another `runInTransaction` for the
 * same database/vault JOINS the transaction that is already open instead of acquiring the non-reentrant vault Mutex a
 * second time.
 *
 * [isOpen] is cleared when the outer transaction finishes, so a coroutine that captured this element (a scope or a stored
 * context) cannot later "join" a transaction that no longer exists; it gets a fresh transaction instead.
 */
class SyncTxOwner(
    val database: SubTrackDatabase,
    val vaultId: String,
    val dispatcher: CoroutineContext,
) : AbstractCoroutineContextElement(Key) {

    private val open = AtomicBoolean(true)

    internal val isOpen: Boolean get() = open.get()
    internal fun close() { open.set(false) }

    companion object Key : CoroutineContext.Key<SyncTxOwner>
}

/**
 * T2: marker for an outer Room transaction opened WITHOUT vault ownership (sync was not bound when it started, so it ran
 * on Room's own transaction thread and never took the vault lock). Nested calls must not silently upgrade such a
 * transaction into a coordinated one: taking the vault Mutex from inside an open Room transaction is the lock-order
 * inversion T1 removed. See [withCoordinatedTransaction].
 */
internal class PlainRoomTransactionScope(
    val database: SubTrackDatabase,
) : AbstractCoroutineContextElement(Key) {

    private val open = AtomicBoolean(true)

    val isOpen: Boolean get() = open.get()
    fun close() { open.set(false) }

    companion object Key : CoroutineContext.Key<PlainRoomTransactionScope>
}

/**
 * T2: a nested business transaction was requested from an incompatible scope — a different database/vault owner, or a
 * vault-bound transaction started inside an uncoordinated one. Failing closed rolls the outer transaction back instead of
 * deadlocking on a lock it already holds or joining a transaction that belongs to something else.
 */
class NestedTransactionScopeException(message: String) : IllegalStateException(message)

/** Single dedicated thread on which every outer business transaction runs; nested commits hop back onto it. */
internal object SyncTransactionThread {
    val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "subtrack-tx").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/**
 * Runs [block] as the single outer business transaction for [vaultId]:
 *   vault lock → transaction thread → Room transaction → owner element → block.
 * Callers without an active vault keep the plain Room transaction (nothing to coordinate).
 *
 * T2: when this call is already inside a transaction, it must not open/acquire anything new:
 *  - same database and same vault (or no vault requested) → reuse the owner, its dispatcher and the Room transaction
 *    that is already open. The inner block is a JOIN, not a savepoint: a failure propagates and aborts the outer one.
 *  - different database or different vault → [NestedTransactionScopeException] (never reuse, never take a second lock).
 *  - vault bound only inside a plain, uncoordinated Room transaction → [NestedTransactionScopeException], because
 *    acquiring the vault Mutex under an open Room transaction would invert the lock order against sync.
 *
 * The decision is made from the coroutine context element only — no thread locals and no global `db.inTransaction()`.
 */
suspend fun <T> withCoordinatedTransaction(
    db: SubTrackDatabase,
    vaultId: String?,
    block: suspend () -> T,
): T {
    val owner = currentTransactionOwner()
    if (owner != null) {
        if (owner.database === db && (vaultId == null || owner.vaultId == vaultId)) {
            // Already inside the one outer transaction for this ledger: hop back to its thread and run the block there.
            // Reuse is safe because the element is only visible while that Room transaction is open.
            return withContext(owner.dispatcher) { block() }
        }
        throw NestedTransactionScopeException(
            "不能在账本 ${owner.vaultId} 的协调事务内再开一个 " +
                "${vaultId ?: "无账本"}${if (owner.database === db) "" else "/另一个数据库"}事务；" +
                "嵌套事务只能复用自己的 database/vault",
        )
    }

    val plain = currentPlainRoomTransaction()
    if (plain != null && vaultId != null) {
        throw NestedTransactionScopeException(
            "账本 $vaultId 的协调事务不能在未协调的 Room 事务内开始：" +
                "在已打开的 Room 事务里再取 vault 锁会与 sync 反向加锁",
        )
    }
    if (vaultId == null) {
        val scope = PlainRoomTransactionScope(db)
        return withContext(scope) {
            try {
                db.withTransaction { block() }
            } finally {
                scope.close()
            }
        }
    }
    return SyncVaultLocks.forVault(vaultId).withLock {
        withContext(SyncTransactionThread.dispatcher) {
            val ownerElement = SyncTxOwner(db, vaultId, SyncTransactionThread.dispatcher)
            db.withTransaction {
                withContext(ownerElement) {
                    try {
                        block()
                    } finally {
                        ownerElement.close()
                    }
                }
            }
        }
    }
}

/** True when the current coroutine is executing inside a live coordinated transaction for exactly this database and vault. */
suspend fun currentTransactionOwner(): SyncTxOwner? =
    kotlin.coroutines.coroutineContext[SyncTxOwner]?.takeIf { it.isOpen }

internal suspend fun currentPlainRoomTransaction(): PlainRoomTransactionScope? =
    kotlin.coroutines.coroutineContext[PlainRoomTransactionScope]?.takeIf { it.isOpen }
