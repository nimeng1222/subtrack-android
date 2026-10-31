package com.subtrack.sync

import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubscriptionEntity

/**
 * F8: the repository is constructed once at app start, but sync is enabled/disabled at runtime (create/join/leave).
 * This delegating gateway keeps exactly one production write path: while sync is off it is a byte-for-byte pass-through,
 * and once a vault is active every repository write goes through the encrypted outbox gateway.
 */
class SwitchableSyncWriteGateway : SyncWriteGateway {
    @Volatile private var delegate: SyncWriteGateway = DirectSyncWriteGateway

    /** B/G5: the immutable session the current delegate belongs to; replaced on switch, never mutated in place. */
    @Volatile private var session: SyncSession? = null

    fun bind(session: SyncSession, gateway: SyncWriteGateway) {
        this.session = session
        delegate = gateway
    }

    fun reset() {
        session = null
        delegate = DirectSyncWriteGateway
    }

    /** Disabled-but-vault-exists: normal local writes plus persistent disabled-edit recording (no upload). */
    fun bindOffline(gateway: SyncWriteGateway) {
        session = null
        delegate = gateway
    }

    fun isCurrent(candidate: SyncSession): Boolean = session === candidate

    val activeSession: SyncSession? get() = session

    val isDirect: Boolean get() = delegate === DirectSyncWriteGateway

    override suspend fun subscription(entity: SubscriptionEntity, deleted: Boolean, mutate: suspend () -> Unit) =
        delegate.subscription(entity, deleted, mutate)

    override suspend fun payment(entity: PaymentRecordEntity, mutate: suspend () -> Unit) =
        delegate.payment(entity, mutate)

    override suspend fun category(entity: CategoryEntity, mutate: suspend () -> Unit) =
        delegate.category(entity, mutate)
}
