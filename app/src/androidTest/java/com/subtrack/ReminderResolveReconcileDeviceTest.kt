package com.subtrack

import androidx.room.withTransaction
import androidx.room.Room
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.BusinessOperation
import com.subtrack.sync.SyncBusinessApplier
import com.subtrack.sync.SyncHeadStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** O4: adopting a remote date through conflict resolution must re-plan reminders immediately (afterApply hook). */
class ReminderResolveReconcileDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val subId = "sub_resolve_rem_0001"

    private suspend fun awaitContainer(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("container not initialised")
    }

    private fun entity(next: LocalDate) = SubscriptionEntity(
        id = subId, name = "R", categoryId = "other", billingCycleType = "MONTHLY",
        anchorDateEpochDay = LocalDate.now().minusDays(1).toEpochDay().toInt(),
        nextChargeDateEpochDay = next.toEpochDay().toInt(), priceMinor = 1000, currency = "CNY",
        status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )

    @Test fun adopting_remote_date_immediately_replans_reminders() = runBlocking {
        val app = awaitContainer()
        val service = app.syncService
        val db = SubTrackDatabase.get(context)
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix(), "device-a")
        val vault = requireNotNull(service.activeVault)
        val today = LocalDate.now()
        app.repository.upsert(
            Subscription(
                id = subId, name = "R", categoryId = "other", cycle = BillingCycleType.MONTHLY,
                anchorDate = today.minusDays(1), nextChargeDate = today.plusDays(2), priceMinor = 1000L,
            ),
        )
        app.reminderService.reconcile()
        assertTrue("a plan must exist before the conflict", db.reminderDao().pendingFor(subId).isNotEmpty())

        val remoteDate = today.plusDays(9)
        val remote = BusinessOperation.SubscriptionUpsert(
            "op_resolve_rem_000001", "device_b", mapOf("device_b" to 1L), false, entity(remoteDate),
        )
        val applier = SyncBusinessApplier(
            db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), SyncHeadStore(db.syncHeadDao()),
            { 0L }, db.syncConflictDao(),
        )
        db.withTransaction { applier.apply(vault, remote) }
        val conflict = service.conflictsRepository().conflicts(vault).first { it.entityId == subId }
        assertTrue(service.conflictsRepository().resolveKeepRemote(vault, conflict.operationId))

        val plans = db.reminderDao().pendingFor(subId)
        assertTrue("plans must be recomputed after the decision", plans.isNotEmpty())
        assertTrue(
            "the adopted remote date must drive the new plan: ${plans.map { it.chargeDateEpochDay }}",
            plans.any { it.chargeDateEpochDay == remoteDate.toEpochDay().toInt() },
        )
        app.repository.upsert(
            Subscription(id = subId, name = "R", categoryId = "other", cycle = BillingCycleType.MONTHLY,
                anchorDate = today.minusDays(1), nextChargeDate = today.plusDays(2), priceMinor = 1000L),
        )
    }
}
