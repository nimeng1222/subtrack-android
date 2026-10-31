package com.subtrack.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Narrow ports so the reconciliation loop can be unit-tested with deterministic fakes and, in production, bound to
 * Room DAOs and [SyncWire] without the loop knowing about either. Uploads and download checkpoints stay strictly
 * separate: an upload acknowledgement never advances the download cursor and vice versa.
 */
interface SyncOutboxPort {
    suspend fun pending(vaultId: String, limit: Int): List<SyncOutboxEntity>
    suspend fun acknowledge(vaultId: String, ids: List<String>)
}

interface SyncRemotePort {
    suspend fun upload(vaultId: String, token: String, item: SyncOutboxEntity): Long
    suspend fun changes(vaultId: String, token: String, after: Long, limit: Int): SyncPage
}

interface SyncStatePort {
    suspend fun state(vaultId: String): SyncStateEntity?
    suspend fun putState(state: SyncStateEntity)
}

/** Atomically apply a decrypted page and advance the checkpoint in the same store transaction; returns new cursor. */
interface SyncApplyPort {
    suspend fun applyPage(vaultId: String, expectedCursor: Long, page: List<RemoteOperation>): Long
}

/**
 * Serial, per-vault reconciliation loop.
 *
 * - One [Mutex] per vault serializes all work so two callers can never interleave uploads/pagination.
 * - Uploads drain the persisted outbox in bounded batches. A lost response leaves the outbox row intact; the replay
 *   reuses the same operationId (server-side idempotency). Uploads are bounded by [maxUploadAttempts] with exponential
 *   backoff, so a throttled/failing endpoint cannot spin forever.
 * - [uploadSpacingMillis] inserts a caller-chosen delay between successive uploads to cooperate with the transport's
 *   per-URL 2s throttle instead of tripping it and mistaking it for a network failure.
 * - Downloads paginate from the persisted checkpoint and stop as soon as the server reports no more changes; the
 *   checkpoint only advances through [SyncApplyPort] after a page is atomically applied, so a crash mid-page resumes
 *   from the last committed cursor rather than replaying or skipping.
 */
class SyncCoordinator(
    private val outbox: SyncOutboxPort,
    private val remote: SyncRemotePort,
    private val state: SyncStatePort,
    private val apply: SyncApplyPort,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val uploadSpacingMillis: Long = 0,
    private val maxUploadAttempts: Int = 3,
    private val pageLimit: Int = 10,
    private val maxPages: Int = 10_000,
) {
    init {
        require(maxUploadAttempts in 1..10)
        // SyncWire.changes caps a server page at 10; keep the port honest rather than silently splitting.
        require(pageLimit in 1..10)
        require(uploadSpacingMillis >= 0)
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(vaultId: String) = locks.getOrPut(vaultId) { Mutex() }

    data class Result(
        val uploaded: Int,
        val pages: Int,
        val appliedOps: Int,
        val uploadIncomplete: Boolean,
        val caughtUp: Boolean,
    )

    suspend fun syncOnce(vaultId: String, token: String): Result = lockFor(vaultId).withLock {
        val initial = requireNotNull(state.state(vaultId)) { "Sync vault not configured" }
        require(initial.enabled) { "Sync is disabled for this vault" }

        val upload = drainUploads(vaultId, token)

        var pages = 0
        var applied = 0
        var cursor = requireNotNull(state.state(vaultId)).cursor
        while (true) {
            val page = remote.changes(vaultId, token, cursor, pageLimit)
            if (page.operations.isNotEmpty()) {
                cursor = apply.applyPage(vaultId, cursor, page.operations)
                applied += page.operations.size
            } else {
                require(page.cursor == cursor) { "Server advanced cursor without operations" }
            }
            pages++
            if (!page.hasMore) break
            if (pages > maxPages) error("Sync pagination did not terminate")
        }
        Result(upload.uploaded, pages, applied, upload.uploadIncomplete, caughtUp = !upload.uploadIncomplete)
    }

    private class UploadOutcome(val uploaded: Int, val uploadIncomplete: Boolean)

    private suspend fun drainUploads(vaultId: String, token: String): UploadOutcome {
        var uploaded = 0
        while (true) {
            val batch = outbox.pending(vaultId, pageLimit)
            if (batch.isEmpty()) return UploadOutcome(uploaded, uploadIncomplete = false)
            val acked = mutableListOf<String>()
            for ((index, item) in batch.withIndex()) {
                if (!uploadWithRetry(vaultId, token, item)) {
                    // Stop early but persist whatever the server already accepted in this batch.
                    if (acked.isNotEmpty()) outbox.acknowledge(vaultId, acked)
                    return UploadOutcome(uploaded, uploadIncomplete = true)
                }
                acked += item.operationId
                uploaded++
                if (uploadSpacingMillis > 0 && index < batch.lastIndex) sleeper(uploadSpacingMillis)
            }
            outbox.acknowledge(vaultId, acked)
        }
    }

    private suspend fun uploadWithRetry(vaultId: String, token: String, item: SyncOutboxEntity): Boolean {
        var attempt = 0
        while (attempt < maxUploadAttempts) {
            try {
                remote.upload(vaultId, token, item)
                return true
            } catch (e: Exception) {
                attempt++
                if (attempt < maxUploadAttempts) sleeper(backoffMillis(attempt))
            }
        }
        return false
    }

    private fun backoffMillis(attempt: Int): Long = 1_000L shl (attempt - 1).coerceIn(0, 5)
}
