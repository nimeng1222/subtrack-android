package com.subtrack.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class SyncCoordinatorTest {
    private val vault = "vault_12345678"
    private val token = "t".repeat(40)

    private fun item(order: Long, id: String = "operation_${order.toString().padStart(12, '0')}") =
        SyncOutboxEntity(vault, id, 1, ByteArray(12), ByteArray(32) { order.toByte() }, order)

    private fun state(cursor: Long = 0) = SyncStateEntity(vault, "device_a", 1, cursor, enabled = true)

    private class FakeOutbox(initial: List<SyncOutboxEntity>) : SyncOutboxPort {
        val rows = initial.toMutableList()
        val acked = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun pending(vaultId: String, limit: Int) = rows.take(limit)
        override suspend fun acknowledge(vaultId: String, ids: List<String>) {
            acked += ids
            rows.removeAll { it.operationId in ids }
        }
    }

    private class FakeRemote : SyncRemotePort {
        val uploaded = Collections.synchronizedList(mutableListOf<String>())
        val pages = mutableMapOf<Long, SyncPage>()
        var failUploadAttempts = 0
        var uploadsBeforeSuccess = 0
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        override suspend fun upload(vaultId: String, token: String, item: SyncOutboxEntity): Long {
            val c = concurrent.incrementAndGet(); maxConcurrent.updateAndGet { maxOf(it, c) }
            try {
                delay(20)
                if (failUploadAttempts > 0) { failUploadAttempts--; throw RuntimeException("transient") }
                if (uploadsBeforeSuccess > 0) {
                    uploadsBeforeSuccess--
                    // Simulate "server accepted but response lost": record then throw.
                    uploaded += item.operationId
                    throw RuntimeException("response lost")
                }
                uploaded += item.operationId
                return item.createdOrder
            } finally { concurrent.decrementAndGet() }
        }

        override suspend fun changes(vaultId: String, token: String, after: Long, limit: Int): SyncPage =
            pages[after] ?: SyncPage(emptyList(), after, hasMore = false)
    }

    private class FakeState(initial: SyncStateEntity) : SyncStatePort {
        var current = initial
        override suspend fun state(vaultId: String) = current
        override suspend fun putState(state: SyncStateEntity) { current = state }
    }

    private class FakeApply(private val failAtCursor: Long? = null) : SyncApplyPort {
        val applied = Collections.synchronizedList(mutableListOf<Int>())
        override suspend fun applyPage(vaultId: String, expectedCursor: Long, page: List<RemoteOperation>): Long {
            if (failAtCursor != null && expectedCursor == failAtCursor) throw RuntimeException("apply interrupted")
            applied += page.map { it.revision.toInt() }
            return page.last().revision
        }
    }

    private fun remoteOp(rev: Long) = RemoteOperation(rev, "operation_${rev.toString().padStart(12, '0')}",
        SyncCrypto.Envelope(keyEpoch = 1, nonce = ByteArray(12), ciphertext = ByteArray(32)))

    @Test fun drains_outbox_and_acknowledges_in_order() = runBlocking {
        val outbox = FakeOutbox(listOf(item(1), item(2), item(3)))
        val remote = FakeRemote()
        val state = FakeState(state())
        val c = SyncCoordinator(outbox, remote, state, FakeApply(), sleeper = {})
        val r = c.syncOnce(vault, token)
        assertEquals(3, r.uploaded)
        assertEquals(listOf("operation_000000000001", "operation_000000000002", "operation_000000000003"), outbox.acked)
        assertTrue(outbox.rows.isEmpty())
        assertFalse(r.uploadIncomplete)
    }

    @Test fun per_vault_work_is_serialized() = runBlocking {
        val outbox = FakeOutbox(listOf(item(1), item(2)))
        val remote = FakeRemote()
        val c = SyncCoordinator(outbox, remote, FakeState(state()), FakeApply(), sleeper = {})
        val results = listOf(async { c.syncOnce(vault, token) }, async { c.syncOnce(vault, token) }).awaitAll()
        // Every operation uploaded exactly once despite two concurrent callers.
        assertEquals(2, remote.uploaded.size)
        assertEquals(1, remote.maxConcurrent.get())
        assertEquals(2, results.sumOf { it.uploaded })
    }

    @Test fun lost_response_replays_same_operation_id_and_acks_once() = runBlocking {
        val outbox = FakeOutbox(listOf(item(1)))
        val remote = FakeRemote().apply { uploadsBeforeSuccess = 1 }
        val c = SyncCoordinator(outbox, remote, FakeState(state()), FakeApply(), sleeper = {}, maxUploadAttempts = 3)
        val r = c.syncOnce(vault, token)
        assertEquals(1, r.uploaded)
        assertEquals(listOf("operation_000000000001"), outbox.acked)
        // Uploaded twice with the same id (idempotent replay), acknowledged once.
        assertEquals(listOf("operation_000000000001", "operation_000000000001"), remote.uploaded)
    }

    @Test fun bounded_retry_stops_and_keeps_pending_row() = runBlocking {
        val outbox = FakeOutbox(listOf(item(1)))
        val remote = FakeRemote().apply { failUploadAttempts = 100 }
        var sleeps = 0
        val c = SyncCoordinator(outbox, remote, FakeState(state()), FakeApply(), sleeper = { sleeps++ }, maxUploadAttempts = 3)
        val r = c.syncOnce(vault, token)
        assertTrue(r.uploadIncomplete)
        assertFalse(r.caughtUp)
        assertEquals(1, outbox.rows.size)     // never dropped
        assertTrue(outbox.acked.isEmpty())
        assertEquals(2, sleeps)               // backoff between the 3 attempts
    }

    @Test fun download_paginates_until_no_more_and_advances_checkpoint() = runBlocking {
        val remote = FakeRemote().apply {
            pages[0] = SyncPage(listOf(remoteOp(1), remoteOp(2)), 2, hasMore = true)
            pages[2] = SyncPage(listOf(remoteOp(3)), 3, hasMore = false)
        }
        val apply = FakeApply()
        val c = SyncCoordinator(FakeOutbox(emptyList()), remote, FakeState(state()), apply, sleeper = {})
        val r = c.syncOnce(vault, token)
        assertEquals(3, r.appliedOps)
        assertEquals(2, r.pages)
        assertEquals(listOf(1, 2, 3), apply.applied)
    }

    @Test fun apply_interruption_keeps_checkpoint_at_last_committed_page() = runBlocking {
        val remote = FakeRemote().apply {
            pages[0] = SyncPage(listOf(remoteOp(1), remoteOp(2)), 2, hasMore = true)
            pages[2] = SyncPage(listOf(remoteOp(3)), 3, hasMore = false)
        }
        val apply = FakeApply(failAtCursor = 2)
        val c = SyncCoordinator(FakeOutbox(emptyList()), remote, FakeState(state()), apply, sleeper = {})
        val error = runCatching { c.syncOnce(vault, token) }.exceptionOrNull()
        assertNotNull(error)
        // First page applied (cursor 2), second page failed; checkpoint stays at 2 so a retry resumes there.
        assertEquals(listOf(1, 2), apply.applied)
    }

    @Test fun process_restart_resumes_remaining_pending_rows() = runBlocking {
        val outbox = FakeOutbox(listOf(item(1), item(2)))
        val failing = FakeRemote().apply { failUploadAttempts = 100 }
        val first = SyncCoordinator(outbox, failing, FakeState(state()), FakeApply(), sleeper = {}, maxUploadAttempts = 1)
        assertTrue(first.syncOnce(vault, token).uploadIncomplete)
        assertEquals(2, outbox.rows.size)
        // Simulate process death and restart: a fresh coordinator over the same persisted outbox drains it.
        val healthy = FakeRemote()
        val second = SyncCoordinator(outbox, healthy, FakeState(state()), FakeApply(), sleeper = {})
        val r = second.syncOnce(vault, token)
        assertEquals(2, r.uploaded)
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun upload_spacing_is_applied_between_successive_uploads() = runBlocking {
        val outbox = FakeOutbox(listOf(item(1), item(2), item(3)))
        val remote = FakeRemote()
        val delays = mutableListOf<Long>()
        val c = SyncCoordinator(outbox, remote, FakeState(state()), FakeApply(), sleeper = { delays += it }, uploadSpacingMillis = 2_100)
        c.syncOnce(vault, token)
        assertEquals(listOf(2_100L, 2_100L), delays)
    }

    @Test fun disabled_vault_is_rejected() = runBlocking {
        val disabled = SyncStateEntity(vault, "device_a", 1, 0, enabled = false)
        val c = SyncCoordinator(FakeOutbox(listOf(item(1))), FakeRemote(), FakeState(disabled), FakeApply(), sleeper = {})
        assertNotNull(runCatching { c.syncOnce(vault, token) }.exceptionOrNull())
    }
}
