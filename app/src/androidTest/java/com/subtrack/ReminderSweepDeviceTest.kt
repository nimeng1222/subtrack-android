package com.subtrack

import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** O5: reminder plans for deleted/orphan subscriptions must be cancelled by reconcile, and non-reminder devices cancel all. */
class ReminderSweepDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val subId = "sub_reminder_sweep_0001"
    private fun sub() = Subscription(
        id = subId, name = "REM", categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.now().minusDays(2), nextChargeDate = LocalDate.now().plusDays(2), priceMinor = 1000L,
    )

    private suspend fun awaitContainer(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("container not initialised")
    }

    @Test fun reconcile_cancels_plans_after_remote_like_soft_delete() = runBlocking {
        val app = awaitContainer()
        val dao = SubTrackDatabase.get(context).reminderDao()
        app.repository.upsert(sub())
        app.reminderService.reconcile()
        val before = dao.pendingFor(subId)
        assertTrue("a normal reconcile must schedule something", before.isNotEmpty())
        // Soft-delete the subscription without going through reconcile (as a remote tombstone apply would).
        app.repository.archive(subId)
        app.reminderService.reconcile()
        assertTrue("plans for a deleted subscription must be cancelled and removed", dao.pendingFor(subId).isEmpty())
        app.repository.upsert(sub())
    }

    @Test fun non_reminder_device_cancels_all_pending_plans_even_orphans() = runBlocking {
        val app = awaitContainer()
        val dao = SubTrackDatabase.get(context).reminderDao()
        app.repository.upsert(sub())
        app.reminderService.reconcile()
        assertTrue(dao.pendingFor(subId).isNotEmpty())
        // Designating another device re-runs reconcile with shouldSchedule=false → cancel by plan rows.
        app.syncService.setDesignatedReminderDevice("peer-device-id")
        assertTrue("no pending plans may remain on a non-reminder device", dao.pendingAll().isEmpty())
        app.syncService.setDesignatedReminderDevice(null)
        app.reminderService.reconcile()
        app.repository.archive(subId)
    }
}
