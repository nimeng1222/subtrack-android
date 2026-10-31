package com.subtrack.sync

/**
 * T1: the repository's outer business transaction is delegated through this coordinator so it can acquire the vault lock
 * FIRST and then run on the dedicated transaction thread (vault → Room order everywhere). Without it, an outer Room
 * transaction followed by a gateway write would invert the order against syncNow and deadlock.
 *
 * T2: [run] is re-entrant for the same database/vault — a nested call joins the transaction that is already open. See
 * `withCoordinatedTransaction` for the exact match/mismatch rules.
 */
interface SyncTransactionCoordinator {
    suspend fun <T> run(block: suspend () -> T): T

    companion object {
        /** Sync disabled / no coordinator available: plain block execution (callers wrap their own DB transaction). */
        val PassThrough: SyncTransactionCoordinator = object : SyncTransactionCoordinator {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
    }
}
