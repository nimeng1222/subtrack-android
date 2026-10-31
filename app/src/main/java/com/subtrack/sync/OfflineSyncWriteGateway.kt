package com.subtrack.sync

import androidx.room.withTransaction
import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.data.db.SubTrackDatabase

/**
 * P1/P3/P4: gateway used while sync is DISABLED (but a vault exists). It performs the ordinary local ledger write and,
 * in the SAME Room transaction, records a persistent disabled-edit row with a stable operation id. Active-gateway writes
 * and remote applies never touch this table, so they can never be reported as disabled-period changes.
 */
class OfflineSyncWriteGateway(
    private val db: SubTrackDatabase,
    private val vaultId: String,
    private val dao: DisabledEditDao = db.disabledEditDao(),
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Q1: a write started under an old vault must not be recorded (or committed) after a session switch. */
    private val admission: suspend () -> Boolean = { true },
) : SyncWriteGateway {
    private suspend fun record(type: String, entityId: String, block: suspend () -> Unit) = db.withTransaction {
        if (!admission()) throw SyncSessionLostException("停用期编辑属于已失效的会话")
        block()
        // One pending row per entity: later edits reuse the same stable opId (the published operation will be the latest
        // state, and a retry never needs to invent a second identity for the same edit).
        // Q3: only an edit that has NOT yet been confirmed/queued may be extended in place. A queued-but-unpublished row
        // already owns a frozen identity for a confirmed snapshot, so a newer edit must get its own row.
        if (dao.pendingFor(vaultId, type, entityId).none { it.queuedAt == null && it.confirmedAt == null }) {
            dao.insert(
                DisabledEditEntity(
                    vaultId = vaultId, entityType = type, entityId = entityId,
                    opId = newDisabledEditOpId(), createdAtMillis = now(),
                ),
            )
        }
        // R1: the binding may have changed while the mutation body ran; throwing here rolls back ledger + edit log.
        if (!admission()) throw SyncSessionLostException("停用期编辑的绑定已失效")
    }

    override suspend fun subscription(entity: SubscriptionEntity, deleted: Boolean, mutate: suspend () -> Unit) =
        record("subscription", entity.id, mutate)

    override suspend fun payment(entity: PaymentRecordEntity, mutate: suspend () -> Unit) =
        record("payment", entity.id, mutate)

    override suspend fun category(entity: CategoryEntity, mutate: suspend () -> Unit) =
        record("category", entity.id, mutate)
}
