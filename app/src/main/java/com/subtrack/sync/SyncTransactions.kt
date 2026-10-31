package com.subtrack.sync

import androidx.room.withTransaction
import com.subtrack.data.db.SubTrackDatabase
import java.security.MessageDigest

data class RemoteOperation(val revision: Long, val operationId: String, val envelope: SyncCrypto.Envelope)

/** Primitive transactions; the product must wire every ledger write before enabling sync. */
class SyncTransactions(private val db: SubTrackDatabase) {
    private val dao get() = db.syncDao()

    suspend fun enqueue(vaultId: String, operationId: String, envelope: SyncCrypto.Envelope,
                        allowDisabled: Boolean = false,
                        admission: suspend () -> Boolean = { true },
                        mutateLedger: suspend () -> Unit) =
        db.withTransaction {
            // R1: both checks live INSIDE the transaction that commits outbox+ledger+heads. A throw here rolls back.
            if (!admission()) throw SyncSessionLostException()
            val state = requireNotNull(dao.state(vaultId)) { "Sync vault not configured" }
            require((state.enabled || allowDisabled) && state.keyEpoch == envelope.keyEpoch && envelope.version == 1 &&
                envelope.nonce.size == 12 && envelope.ciphertext.size in 16..49168)
            require(operationId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
            val order = Math.addExact(dao.lastOrder(vaultId), 1)
            mutateLedger()
            if (!admission()) throw SyncSessionLostException()
            dao.insertOutbox(SyncOutboxEntity(vaultId, operationId, envelope.keyEpoch,
                envelope.nonce.copyOf(), envelope.ciphertext.copyOf(), order))
            if (!admission()) throw SyncSessionLostException()
        }

    suspend fun acknowledge(vaultId: String, operationIds: List<String>) = db.withTransaction {
        require(operationIds.size <= 100 && operationIds.distinct().size == operationIds.size)
        operationIds.forEach { dao.acknowledge(vaultId, it) }
    }

    /** Authenticated decryption and domain application precede the checkpoint in the same transaction. */
    suspend fun applyPage(vaultId: String, expectedCursor: Long, page: List<RemoteOperation>,
                          keyForEpoch: suspend (Int) -> ByteArray,
                          admission: suspend () -> Boolean = { true },
                          apply: suspend (RemoteOperation, ByteArray) -> Unit) =
        db.withTransaction {
            // M1: the session check lives INSIDE the commit transaction, so an invalidation that happened during the
            // network wait cannot slip a stale page (and its cursor advance) into the store.
            if (!admission()) throw SyncSessionLostException()
            val state = requireNotNull(dao.state(vaultId))
            require(state.enabled && state.cursor == expectedCursor && expectedCursor >= 0 && page.size <= 100)
            var cursor = expectedCursor
            for (item in page) {
                require(item.revision > cursor && item.operationId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
                require(item.envelope.version == 1 && item.envelope.keyEpoch > 0 && item.envelope.nonce.size == 12 &&
                    item.envelope.ciphertext.size in 16..49168)
                val digest = digest(item.envelope)
                val previous = dao.received(vaultId, item.operationId)
                if (previous == null) {
                    val key = keyForEpoch(item.envelope.keyEpoch)
                    val plaintext = try { SyncCrypto.decrypt(key, vaultId, item.operationId, item.envelope) }
                        finally { key.fill(0) }
                    try { apply(item, plaintext) } finally { plaintext.fill(0) }
                    dao.insertReceived(SyncReceivedEntity(vaultId, item.operationId, digest, item.revision))
                } else require(previous.digest == digest && previous.serverRevision == item.revision) { "Conflicting operation replay" }
                cursor = item.revision
            }
            // N1: validate the session again at the END of the same transaction that advances the cursor.
            if (!admission()) throw SyncSessionLostException()
            if (cursor != expectedCursor) dao.advanceCursor(state.vaultId, cursor)
        }

    private fun digest(envelope: SyncCrypto.Envelope): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(byteArrayOf(envelope.version.toByte()))
        md.update(java.nio.ByteBuffer.allocate(4).putInt(envelope.keyEpoch).array())
        md.update(envelope.nonce); md.update(envelope.ciphertext)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
