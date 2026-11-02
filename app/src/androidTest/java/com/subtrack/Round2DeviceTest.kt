package com.subtrack

import android.content.Intent
import android.app.NotificationManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.room.Room
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.repository.SubscriptionRepositoryImpl
import com.subtrack.data.settings.SettingsState
import com.subtrack.domain.model.*
import com.subtrack.domain.repository.PaymentRecord
import com.subtrack.domain.state.saveEditorDraft
import com.subtrack.domain.state.EditorSaveResult
import com.subtrack.domain.state.SubscriptionActions
import com.subtrack.reminder.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class Round2DeviceTest {
    private val instrumentation get() = Round2Instrumentation.instance
    private val context get() = instrumentation.targetContext
    private val today = LocalDate.of(2026, 10, 27)
    private val clock = object : AppClock {
        override fun today() = today
        override fun nowMillis() = today.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        override fun reminderTime() = LocalTime.of(9, 0)
    }
    private fun sub(date: LocalDate = today) = Subscription(id = UUID.randomUUID().toString(), name = "ROUND2 TEST", categoryId = "other",
        cycle = BillingCycleType.MONTHLY, anchorDate = date.minusMonths(1), nextChargeDate = date, priceMinor = 1000L)

    @Test fun room_actions_are_atomic_under_concurrent_confirmation_and_all_old_actions_are_inert() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val repo = SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db)
            val s = sub(); repo.upsert(s)
            val handler = NotificationActionHandler(repo, clock) {}
            val accepted = (1..12).map { async(Dispatchers.IO) { handler.handle(s.id, today.toEpochDay().toInt(), NotificationAction.CONFIRM) } }.awaitAll()
            assertEquals(1, accepted.count { it })
            assertEquals(1, repo.paymentsOf(s.id).size)
            val next = repo.get(s.id)!!
            assertTrue(next.nextChargeDate!! > today)
            for (action in NotificationAction.entries) assertFalse(handler.handle(s.id, today.toEpochDay().toInt(), action))
            assertEquals(next, repo.get(s.id))
            assertEquals(1, repo.paymentsOf(s.id).size)
        } finally { db.close() }
    }

    @Test fun room_transaction_rolls_back_and_deleted_history_is_preserved_but_excluded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val repo = SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db)
            val s = sub(); repo.upsert(s)
            try {
                repo.runInTransaction {
                    repo.recordPayments(listOf(PaymentRecord("rollback", s.id, today, 2000)))
                    repo.upsert(s.copy(nextChargeDate = today.plusMonths(1)))
                    error("intentional rollback")
                }
            } catch (_: IllegalStateException) { }
            assertTrue(repo.paymentsOf(s.id).isEmpty())
            assertEquals(today, repo.get(s.id)!!.nextChargeDate)
            repo.recordPayments(listOf(PaymentRecord("kept", s.id, today, 2000, "USD")))
            assertEquals("USD", repo.paymentsBetween(today, today).single().currency)
            repo.archive(s.id)
            assertEquals(1, repo.paymentsOf(s.id).size)
            assertTrue(repo.paymentsBetween(today, today).isEmpty())
            assertEquals(0L to 0, repo.paymentsSummary(today, today))
        } finally { db.close() }
    }

    @Test fun room_notification_actions_validate_today_trial_one_time_pause_and_missing_period() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val repo = SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db)
            val handler = NotificationActionHandler(repo, clock) {}
            for (type in listOf(BillingCycleType.MONTHLY, BillingCycleType.ONE_TIME)) {
                val s = sub().copy(cycle = type); repo.upsert(s)
                assertTrue(handler.handle(s.id, today.toEpochDay().toInt(), NotificationAction.CONFIRM))
                assertEquals(1, repo.paymentsOf(s.id).size)
            }
            val trial = sub(today.plusDays(5)).copy(status = SubscriptionStatus.TRIAL, trialEndDate = today)
            repo.upsert(trial)
            assertFalse(handler.handle(trial.id, today.plusDays(5).toEpochDay().toInt(), NotificationAction.CONFIRM))
            assertTrue(handler.handle(trial.id, today.toEpochDay().toInt(), NotificationAction.CONFIRM))
            assertEquals(SubscriptionStatus.ACTIVE, repo.get(trial.id)!!.status)
            assertNull(repo.get(trial.id)!!.trialEndDate)
            for (status in listOf(SubscriptionStatus.PAUSED, SubscriptionStatus.CANCELED, SubscriptionStatus.EXPIRED)) {
                val s = sub().copy(status = status); repo.upsert(s)
                for (action in NotificationAction.entries) assertFalse(handler.handle(s.id, today.toEpochDay().toInt(), action))
            }
            val future = sub(today.plusDays(1)); repo.upsert(future)
            for (action in NotificationAction.entries) assertFalse(handler.handle(future.id, null, action))
            assertTrue(repo.paymentsOf(future.id).isEmpty())
            // 1.9.7 起未到期不确认：提前通知上点“已续费”不得返回成功，也不得写空流水或推进日期。
            val notices = mutableListOf<String>()
            val guarded = NotificationActionHandler(repo, clock, onNotice = { notices += it }) {}
            assertFalse(guarded.handle(future.id, future.nextChargeDate!!.toEpochDay().toInt(), NotificationAction.CONFIRM))
            assertTrue(repo.paymentsOf(future.id).isEmpty())
            assertEquals(future, repo.get(future.id))
            assertEquals(listOf(SubscriptionActions.NOT_DUE_CONFIRM_MESSAGE), notices)
        } finally { db.close() }
    }

    @Test fun actual_receiver_repeated_confirmation_and_legacy_intents_share_database_claims() = runBlocking {
        val app = context.applicationContext as SubTrackApp
        val c = app.container; val settings = c.awaitSettings()
        val now = c.clock.today()
        val s = sub(now)
        c.repository.upsert(s)
        fun action(kind: String, day: LocalDate) = Intent(context, ReminderActionReceiver::class.java).apply {
            action = kind
            putExtra(AlarmReminderScheduler.EXTRA_SUBSCRIPTION_ID, s.id)
            putExtra(AlarmReminderScheduler.EXTRA_CHARGE_DATE, day.toEpochDay().toInt())
        }
        try {
            repeat(8) { context.sendBroadcast(action(ReminderActionReceiver.ACTION_CONFIRM, now)) }
            withTimeout(8000) { while (c.repository.paymentsOf(s.id).isEmpty()) delay(50) }
            delay(300)
            assertEquals(1, c.repository.paymentsOf(s.id).size)
            val snapshot = c.repository.get(s.id)!!
            for (kind in listOf(ReminderActionReceiver.ACTION_CONFIRM, ReminderActionReceiver.ACTION_MUTE, ReminderActionReceiver.ACTION_SNOOZE))
                context.sendBroadcast(action(kind, now))
            context.sendBroadcast(Intent(context, ReminderReceiver::class.java).apply {
                putExtra(AlarmReminderScheduler.EXTRA_SUBSCRIPTION_ID, s.id)
                putExtra(AlarmReminderScheduler.EXTRA_CHARGE_DATE, now.toEpochDay().toInt())
            })
            delay(400)
            assertEquals(snapshot, c.repository.get(s.id))
            assertEquals(1, c.repository.paymentsOf(s.id).size)
            assertTrue(SubTrackDatabase.get(context).reminderDao().allFor(s.id).none { it.kind == "SNOOZE" })
        } finally { c.repository.archive(s.id); c.reminderService.cancelFor(s.id) }
    }

    @Test fun actual_legacy_receiver_delivers_once_then_rejects_paused_subscription() = runBlocking {
        val c = (context.applicationContext as SubTrackApp).container
        c.awaitSettings()
        val s = sub(c.clock.today().minusDays(1))
        c.repository.upsert(s)
        val dao = SubTrackDatabase.get(context).reminderDao()
        val manager = context.getSystemService(NotificationManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(AlarmReminderScheduler.EXTRA_SUBSCRIPTION_ID, s.id)
            putExtra(AlarmReminderScheduler.EXTRA_CHARGE_DATE, s.nextChargeDate!!.toEpochDay().toInt())
        }
        try {
            repeat(5) { context.sendBroadcast(intent) }
            withTimeout(8000) { while (dao.allFor(s.id).none { it.kind == "ONSET" && it.state == "FIRED" }) delay(50) }
            delay(200)
            val onset = dao.allFor(s.id).single { it.kind == "ONSET" }
            assertEquals(1, manager.activeNotifications.count { it.id == onset.id.toInt() })
            c.repository.upsert(s.copy(status = SubscriptionStatus.PAUSED))
            manager.cancel(onset.id.toInt())
            context.sendBroadcast(intent)
            delay(300)
            assertEquals(0, manager.activeNotifications.count { it.id == onset.id.toInt() })
            assertEquals(1, dao.allFor(s.id).count { it.kind == "ONSET" })
        } finally {
            dao.allFor(s.id).forEach { manager.cancel(it.id.toInt()) }
            c.repository.archive(s.id); c.reminderService.cancelFor(s.id)
        }
    }

    @Test fun alarm_manager_dispatches_modern_row_to_receiver_and_claims_once() = runBlocking {
        val c = (context.applicationContext as SubTrackApp).container
        c.awaitSettings()
        val s = sub(c.clock.today().minusDays(1)); c.repository.upsert(s)
        val dao = SubTrackDatabase.get(context).reminderDao()
        val manager = context.getSystemService(NotificationManager::class.java)
        val fireAt = System.currentTimeMillis() + 1800
        val id = dao.insertAll(listOf(com.subtrack.data.db.ScheduledReminderEntity(subscriptionId = s.id,
            kind = "ONSET", offsetDays = 0, chargeDateEpochDay = s.nextChargeDate!!.toEpochDay().toInt(),
            fireAtUtcMillis = fireAt, createdAtUtcMillis = System.currentTimeMillis()))).single()
        try {
            AlarmReminderScheduler(context).schedule(id.toInt(), fireAt, s.name, "alarm integration", s.id,
                s.nextChargeDate!!.toEpochDay().toInt(), id.toInt(), id)
            withTimeout(20000) { while (dao.byId(id)?.state != "FIRED") delay(50) }
            delay(200)
            assertEquals(1, manager.activeNotifications.count { it.id == id.toInt() })
            c.reminderService.deliverExact(id)
            assertEquals(1, dao.allFor(s.id).count { it.kind == "DELIVERY" })
        } finally {
            manager.cancel(id.toInt()); c.repository.archive(s.id); c.reminderService.cancelFor(s.id)
        }
    }

    @Test fun persisted_rate_decoder_rejects_legacy_extremes_without_affecting_valid_rates() {
        val store = com.subtrack.data.settings.SettingsStore(context)
        assertEquals(mapOf("USD" to 720L), store.decodeRates("USD=720;EUR=1000000000000000000;GBP=-1"))
    }

    // Root cause of the former flaky disabled-button assertion (see docs/FEATURE-REPORT-NETWORK-RESUMED-2026-10-29.md):
    // the old test walked the UiAutomation accessibility parent chain reading isEnabled after sleep-based idling,
    // which raced recomposition and did not reliably reflect Compose's disabled semantics. It is replaced by
    // EditorValidationComposeTest, which drives the real production editor through stable testTags and the official
    // Compose test rule (assertIsNotEnabled), preserving the original requirement that invalid day counts cannot be
    // saved from either save control.

    @Test fun compose_gate_blocks_delayed_settings_cancel_recreation_and_background() {
        var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val rendered = AtomicInteger(0)
        val state = mutableStateOf<SettingsState>(SettingsState.Loading)
        var success: (() -> Unit)? = null
        var cancel: (() -> Unit)? = null
        fun mount() = instrumentation.runOnMainSync {
            activity.setContent {
                LockGate(state.value, authenticate = { ok, no -> success = ok; cancel = no }) {
                    SideEffect { rendered.incrementAndGet() }
                }
            }
        }
        fun idle() { instrumentation.waitForIdleSync(); Thread.sleep(200); instrumentation.waitForIdleSync() }
        try {
            mount(); idle(); assertEquals(0, rendered.get())
            instrumentation.runOnMainSync { state.value = SettingsState.Loaded(AppSettings(appLockEnabled = true)) }
            idle(); assertNotNull(cancel)
            instrumentation.runOnMainSync { cancel!!() }; idle(); assertEquals(0, rendered.get())
            instrumentation.runOnMainSync { success!!() }; idle(); assertTrue(rendered.get() > 0)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            activity = monitor.waitForActivityWithTimeout(5000) as? MainActivity ?: error("Activity recreation timed out")
            instrumentation.removeMonitor(monitor)
            rendered.set(0); mount(); idle(); assertEquals(0, rendered.get())
            val oldSuccess = success!!
            instrumentation.runOnMainSync {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            var stopped = false
            repeat(50) {
                instrumentation.runOnMainSync { stopped = !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                if (!stopped) Thread.sleep(100)
            }
            assertTrue("Activity must actually stop before testing stale callbacks", stopped)
            instrumentation.runOnMainSync { oldSuccess() }
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            var started = false
            repeat(50) {
                instrumentation.runOnMainSync { started = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                if (!started) Thread.sleep(100)
            }
            idle(); assertEquals(0, rendered.get())
            assertTrue("Activity must return to foreground", started)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun acceptance_editor_draft_cannot_roll_back_concurrent_notification_confirmation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        try {
            val repo = SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock, db)
            val original = sub(); repo.upsert(original)
            val editorDraft = original.copy(name="Edited name")
            assertTrue(NotificationActionHandler(repo, clock) {}.handle(original.id,today.toEpochDay().toInt(),NotificationAction.CONFIRM))
            val advanced = repo.get(original.id)!!.nextChargeDate
            // Exercise the transactional entry used by MainActivity.onSave, with the actual opening snapshot.
            val result = repo.saveEditorDraft(original, editorDraft)
            assertTrue(result is EditorSaveResult.Conflict)
            assertEquals("Edited name", editorDraft.name)
            assertEquals(1, repo.paymentsOf(original.id).size)
            assertEquals("Editor save must retain the concurrently confirmed period",advanced,repo.get(original.id)!!.nextChargeDate)
        } finally { db.close() }
    }

}
