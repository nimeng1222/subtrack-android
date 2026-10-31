package com.subtrack

import androidx.room.Room
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.repository.SubscriptionRepositoryImpl
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.reminder.ReminderNotifier
import com.subtrack.reminder.ReminderPlanner
import com.subtrack.reminder.ReminderScheduler
import com.subtrack.reminder.ReminderService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * N4 reminder-device role gate: when this device is NOT the reminder device, `reconcile` must cancel local plans and
 * schedule nothing. Default (role unset) keeps scheduling (existing single-device behaviour).
 */
class ReminderRoleGateDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val today = LocalDate.of(2026, 10, 27)
    private val clock = object : AppClock {
        override fun today() = today
        override fun nowMillis() = today.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        override fun reminderTime() = LocalTime.of(9, 0)
    }

    private class FakeScheduler : ReminderScheduler {
        override fun schedule(reminderId: Int, fireAtMillis: Long, title: String, body: String,
                              subscriptionId: String, chargeDateEpochDay: Int, notificationId: Int, reminderRowId: Long) = Unit
        override fun cancel(reminderId: Int) = Unit
    }
    private class FakeNotifier : ReminderNotifier {
        override fun show(title: String, body: String, subscriptionId: String, chargeDateEpochDay: Int, notificationId: Int) = Unit
    }

    @Test fun non_reminder_device_schedules_nothing_and_cancels_existing() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val repo = SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db)
            val sub = Subscription("sub_role_0000000001", "ROLE", "other", cycle = BillingCycleType.MONTHLY,
                anchorDate = today, nextChargeDate = today.plusDays(3), priceMinor = 1000L)
            repo.upsert(sub)
            var allow = true
            val svc = ReminderService(
                FakeNotifier(), repo, db.reminderDao(), ReminderPlanner(clock), FakeScheduler(), clock,
                { AppSettings() }, {}, { allow },
            )
            svc.reconcile()
            assertTrue("reminder device must schedule", db.reminderDao().pendingFor(sub.id).isNotEmpty())
            allow = false
            svc.reconcile()
            assertTrue("non-reminder device must schedule nothing", db.reminderDao().pendingFor(sub.id).isEmpty())
        } finally { db.close() }
    }
}
