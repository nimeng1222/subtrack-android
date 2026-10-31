package com.subtrack.sync

import androidx.room.withTransaction
import com.subtrack.data.db.SubTrackDatabase

/** Bounded primitives plus the transactional business-write enqueue and the tested coordinator binding. */
class SyncEngine(
    private val db: SubTrackDatabase,
    private val wire: SyncWire,
    private val admission: suspend () -> Boolean = { true },
) : SyncEnqueuePort {
    private val transactions = SyncTransactions(db)

    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    suspend fun uploadOne(vaultId: String, token: String): Boolean {
        if (!admission()) throw SyncSessionLostException()
        val state = requireNotNull(db.syncDao().state(vaultId))
        require(state.enabled)
        val item = db.syncDao().pending(vaultId, 1).firstOrNull() ?: return false
        wire.upload(token, item)
        // A lost response leaves the outbox intact; replay uses the same operation ID.
        transactions.acknowledge(vaultId, listOf(item.operationId))
        return true
    }

    suspend fun downloadPage(vaultId: String, token: String, keyForEpoch: suspend (Int) -> ByteArray,
                             apply: suspend (RemoteOperation, ByteArray) -> Unit): Boolean {
        if (!admission()) throw SyncSessionLostException()
        val state = requireNotNull(db.syncDao().state(vaultId))
        require(state.enabled)
        val page = wire.changes(token, state.cursor)
        // Any failure in decryption or application rolls back data, receipts and checkpoint.
        transactions.applyPage(vaultId, state.cursor, page.operations, keyForEpoch, admission = admission, apply = apply)
        return page.hasMore
    }

    /**
     * Business write that must be enqueued atomically with the ledger mutation: [mutateLedger] (the real Room write)
     * and the encrypted outbox row commit in the same transaction, so an offline edit is never lost and is never sent
     * before it is durably stored.
     */
    suspend fun enqueueBusiness(
        vaultId: String,
        operation: BusinessOperation,
        keyProvider: suspend (Int) -> ByteArray,
        mutateLedger: suspend () -> Unit,
    ) = db.withTransaction {
        // M1: business enqueue is a production commit path too — a stale session must not insert into the new vault.
        if (!admission()) throw SyncSessionLostException()
        // Key epoch is read, the payload encrypted and the outbox row inserted inside one transaction, so a concurrent
        // key rotation cannot tear the operation's epoch from the committed row (F4).
        val state = requireNotNull(db.syncDao().state(vaultId)) { "Sync vault not configured" }
        require(state.enabled)
        val key = keyProvider(state.keyEpoch)
        val envelope = try {
            SyncCrypto.encrypt(key, vaultId, operation.operationId, state.keyEpoch, BusinessCodec.encode(operation))
        } finally {
            key.fill(0)
        }
        transactions.enqueue(vaultId, operation.operationId, envelope, admission = admission, mutateLedger = mutateLedger)
        // N1: end-of-transaction check (throw rolls the ledger+outbox commit back).
        if (!admission()) throw SyncSessionLostException()
    }

    override suspend fun enqueue(
        vaultId: String,
        operation: BusinessOperation,
        keyProvider: suspend (Int) -> ByteArray,
        mutateLedger: suspend () -> Unit,
    ) = enqueueBusiness(vaultId, operation, keyProvider, mutateLedger)

    /**
     * Q1/Q3: enqueue an ALREADY-frozen, already-encrypted operation (confirmed snapshot). Admission is re-checked at the
     * start AND at the end of the same transaction, exactly like the normal business path.
     */
    suspend fun enqueueEncrypted(
        vaultId: String,
        operationId: String,
        envelope: com.subtrack.sync.SyncCrypto.Envelope,
        allowDisabled: Boolean,
        mutateLedger: suspend () -> Unit,
    ) {
        // No outer pre/post checks: the transaction performs admission at its start, before the outbox insert and at
        // the very end, so an invalidation during the body rolls the whole commit back (R1).
        transactions.enqueue(vaultId, operationId, envelope, allowDisabled, admission = admission, mutateLedger = mutateLedger)
    }

    /** Bind the tested [SyncCoordinator] loop to the real Room store and wire transport. */
    private suspend fun guard() { if (!admission()) throw SyncSessionLostException() }

    fun coordinator(
        keyForEpoch: suspend (Int) -> ByteArray,
        apply: suspend (RemoteOperation, ByteArray) -> Unit,
    ): SyncCoordinator = SyncCoordinator(
        outbox = object : SyncOutboxPort {
            override suspend fun pending(vaultId: String, limit: Int): List<SyncOutboxEntity> { guard(); return db.syncDao().pending(vaultId, limit) }
            override suspend fun acknowledge(vaultId: String, ids: List<String>) { guard(); transactions.acknowledge(vaultId, ids); guard() }
        },
        remote = object : SyncRemotePort {
            override suspend fun upload(vaultId: String, token: String, item: SyncOutboxEntity): Long {
                guard(); val result = wire.upload(token, item); guard(); return result
            }
            override suspend fun changes(vaultId: String, token: String, after: Long, limit: Int): SyncPage {
                guard(); val page = wire.changes(token, after, limit); guard(); return page
            }
        },
        state = object : SyncStatePort {
            override suspend fun state(vaultId: String) = db.syncDao().state(vaultId)
            override suspend fun putState(state: SyncStateEntity) { guard(); db.syncDao().putState(state) }
        },
        apply = object : SyncApplyPort {
            override suspend fun applyPage(vaultId: String, expectedCursor: Long, page: List<RemoteOperation>): Long {
                guard()
                transactions.applyPage(vaultId, expectedCursor, page, keyForEpoch, admission = admission, apply = apply)
                guard()
                return requireNotNull(db.syncDao().state(vaultId)).cursor
            }
        },
        // Cooperate with NetworkTransport's per-URL 2s throttle instead of tripping it on back-to-back uploads.
        uploadSpacingMillis = 2_100,
    )
}
