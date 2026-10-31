package com.subtrack

import androidx.room.Room
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.ScheduledReminderEntity
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.reminder.ReminderNotifier
import com.subtrack.reminder.ReminderPlanner
import com.subtrack.reminder.ReminderScheduler
import com.subtrack.reminder.ReminderService
import com.subtrack.sync.NestedTransactionScopeException
import com.subtrack.sync.SyncProductService
import com.subtrack.sync.SyncVaultLocks
import com.subtrack.sync.currentTransactionOwner
import com.subtrack.sync.withCoordinatedTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * T2: a business transaction nested inside another one for the same database/vault must JOIN the open transaction.
 * The vault Mutex is not reentrant, so re-acquiring it inside the outer block deadlocks against itself; only reusing
 * the [com.subtrack.sync.SyncTxOwner] (and with it the Room transaction and its thread) is correct.
 *
 * These tests use the real AppContainer repository/ReminderService against a real vault, so the coordinator, the outbox
 * gateway, the causal clocks and the reminder compatibility path are all production code.
 */
class NestedTransactionDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val setupToken get() = com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix()
    private fun db() = SubTrackDatabase.get(context)

    private suspend fun awaitContainer(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("container not initialised")
    }

    /** A real, enabled vault: only then does the repository route outer transactions through the coordinator. */
    private suspend fun vault(app: AppContainer, name: String): String {
        val service: SyncProductService = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-$name")
        return requireNotNull(service.activeVault)
    }

    private fun sub(id: String, name: String, charge: LocalDate = LocalDate.of(2026, 11, 1), muted: LocalDate? = null) =
        Subscription(
            id = id, name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
            anchorDate = charge.minusMonths(1), nextChargeDate = charge, priceMinor = 1000L, mutedUntil = muted,
        )

    private fun today(): LocalDate = LocalDate.now()

    /** Deterministic "late in the day" clock: every 09:00 reminder of today (or earlier) is due. */
    private fun eveningClock(today: LocalDate) = object : AppClock {
        override fun today() = today
        override fun nowMillis() = today.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        override fun reminderTime() = LocalTime.of(9, 0)
    }

    private fun millisOf(date: LocalDate, time: LocalTime) =
        date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class CountingNotifier : ReminderNotifier {
        val delivered = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        override fun show(title: String, body: String, subscriptionId: String, chargeDateEpochDay: Int, notificationId: Int) {
            delivered += subscriptionId to title
        }
        fun count(subscriptionId: String) = delivered.count { it.first == subscriptionId }
    }

    private object SilentScheduler : ReminderScheduler {
        override fun schedule(reminderId: Int, fireAtMillis: Long, title: String, body: String,
                              subscriptionId: String, chargeDateEpochDay: Int, notificationId: Int, reminderRowId: Long) = Unit
        override fun cancel(reminderId: Int) = Unit
    }

    private fun reminderService(app: AppContainer, clock: AppClock, notifier: ReminderNotifier) = ReminderService(
        notifier = notifier,
        repository = app.repository,
        reminderDao = db().reminderDao(),
        planner = ReminderPlanner(clock),
        scheduler = SilentScheduler,
        clock = clock,
    )

    // ---------------------------------------------------------------- T2 core regressions

    @Test fun truly_nested_repository_transactions_must_reuse_owner() = runBlocking {
        val app = awaitContainer()
        val vaultId = vault(app, "t2-nested")
        val id = "sub_t2_nested_${System.nanoTime()}"
        var outerOwner: com.subtrack.sync.SyncTxOwner? = null
        var innerOwner: com.subtrack.sync.SyncTxOwner? = null
        val completed = withTimeoutOrNull(10_000) {
            app.repository.runInTransaction {
                val owner = requireNotNull(currentTransactionOwner())
                outerOwner = owner
                assertTrue("owner must carry the calling database", owner.database === db())
                assertEquals("owner must carry the calling vault", vaultId, owner.vaultId)
                withContext(Dispatchers.IO) {
                    app.repository.runInTransaction {
                        innerOwner = requireNotNull(currentTransactionOwner())
                        app.repository.upsert(sub(id, "TRUE-NESTING"))
                        // A payment enqueued from the nested transaction must land in the same outer commit.
                        app.repository.recordPayment(id, today(), 1000L)
                    }
                }
            }
            true
        } ?: false
        assertTrue(
            "A same-database/same-vault nested runInTransaction must reuse its owner instead of acquiring the " +
                "non-reentrant vault Mutex again",
            completed,
        )
        assertTrue("the inner transaction must join the SAME owner instance", innerOwner != null && innerOwner === outerOwner)
        assertEquals("TRUE-NESTING", app.repository.get(id)?.name)
        assertEquals("payment from the nested block commits with the outer transaction", 1, app.repository.paymentsOf(id).size)
        assertTrue("both writes are in the outbox of this fresh vault", db().syncDao().pendingCount(vaultId) >= 2)
    }

    @Test fun muted_reminder_exact_delivery_must_not_self_deadlock() = runBlocking {
        val app = awaitContainer()
        vault(app, "t2-muted")
        val today = today()
        val clock = eveningClock(today)
        val id = "sub_t2_muted_${System.nanoTime()}"
        // Explicit mute for today plus a still-PENDING node: deliverRows -> normalizeLegacySnooze opens a second
        // runInTransaction with mutedUntil != null, which is the production self-lock.
        app.repository.upsert(sub(id, "MUTED", charge = today, muted = today))
        db().reminderDao().insertAll(
            listOf(
                ScheduledReminderEntity(
                    subscriptionId = id, kind = "ONSET", offsetDays = 0,
                    chargeDateEpochDay = today.toEpochDay().toInt(),
                    fireAtUtcMillis = millisOf(today, LocalTime.of(9, 0)),
                    createdAtUtcMillis = clock.nowMillis() - 3_600_000L,
                ),
            ),
        )
        val rowId = db().reminderDao().pendingFor(id).single().id
        val notifier = CountingNotifier()
        val reminder = reminderService(app, clock, notifier)

        val completed = withTimeoutOrNull(10_000) { reminder.deliverExact(rowId); true } ?: false
        assertTrue(
            "deliverExact -> deliverRows outer transaction -> normalizeLegacySnooze inner transaction must complete " +
                "for a muted subscription",
            completed,
        )
        assertEquals("a muted node must never be sent", 0, notifier.count(id))
        assertTrue("the muted node must be retired, not left PENDING", db().reminderDao().byId(rowId)?.state != "PENDING")
    }

    // ---------------------------------------------------------------- nesting semantics

    @Test fun deeply_nested_transactions_commit_across_io_and_default() = runBlocking {
        val app = awaitContainer()
        val vaultId = vault(app, "t2-deep")
        val id = "sub_t2_deep_${System.nanoTime()}"
        val paymentDay = today()
        val owners = java.util.concurrent.CopyOnWriteArrayList<com.subtrack.sync.SyncTxOwner>()
        val completed = withTimeoutOrNull(15_000) {
            app.repository.runInTransaction {
                owners += requireNotNull(currentTransactionOwner())
                app.repository.upsert(sub(id, "LEVEL-0"))
                withContext(Dispatchers.Default) {
                    app.repository.runInTransaction {
                        owners += requireNotNull(currentTransactionOwner())
                        app.repository.recordPayment(id, paymentDay, 100L)
                        withContext(Dispatchers.IO) {
                            app.repository.runInTransaction {
                                owners += requireNotNull(currentTransactionOwner())
                                app.repository.recordPayment(id, paymentDay.minusDays(1), 200L)
                                app.repository.upsert(sub(id, "LEVEL-2"))
                            }
                        }
                    }
                }
            }
            true
        } ?: false
        assertTrue("three levels of runInTransaction across Default/IO must complete", completed)
        assertEquals("every level must see the same owner instance", 3, owners.size)
        assertTrue("nested levels must reuse one owner, not create new ones", owners.all { it === owners.first() })
        assertEquals("LEVEL-2", app.repository.get(id)?.name)
        assertEquals("all three levels commit together", 2, app.repository.paymentsOf(id).size)
        assertEquals(4, db().syncDao().pendingCount(vaultId))
    }

    @Test fun inner_nested_transaction_failure_rolls_back_outer_commit() = runBlocking {
        val app = awaitContainer()
        val vaultId = vault(app, "t2-rollback")
        val outerId = "sub_t2_outer_${System.nanoTime()}"
        val innerId = "sub_t2_inner_${System.nanoTime()}"
        val outcome = runCatching {
            app.repository.runInTransaction<Unit> {
                app.repository.upsert(sub(outerId, "OUTER"))
                withContext(Dispatchers.IO) {
                    app.repository.runInTransaction<Unit> {
                        app.repository.upsert(sub(innerId, "INNER"))
                        app.repository.recordPayment(innerId, today(), 500L)
                        error("injected inner business failure")
                    }
                }
            }
        }
        assertTrue("the inner failure must propagate", outcome.isFailure)
        assertNull("the outer ledger write rolls back", app.repository.get(outerId))
        assertNull("the inner ledger write rolls back", app.repository.get(innerId))
        assertTrue("the inner payment rolls back", app.repository.paymentsOf(innerId).isEmpty())
        assertEquals("no outbox row may survive", 0, db().syncDao().pendingCount(vaultId))
        assertTrue("no head may survive", db().syncHeadDao().heads(vaultId, "subscription", innerId).isEmpty())
        assertTrue("no head may survive for the outer write", db().syncHeadDao().heads(vaultId, "subscription", outerId).isEmpty())
    }

    @Test fun nested_transaction_for_other_vault_is_rejected() = runBlocking {
        val app = awaitContainer()
        val vaultId = vault(app, "t2-other-vault")
        val otherVault = "vault_t2_other_${System.nanoTime()}"
        val id = "sub_t2_other_vault_${System.nanoTime()}"
        val outcome = runCatching {
            app.repository.runInTransaction<Unit> {
                app.repository.upsert(sub(id, "OUTER"))
                // Not reusable: a different vault means a different lock and a different outbox/admission domain.
                withCoordinatedTransaction(db(), otherVault) { Unit }
            }
        }
        assertTrue(
            "a nested transaction for another vault must fail closed, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is NestedTransactionScopeException,
        )
        assertNull("the outer transaction rolls back", app.repository.get(id))
        assertEquals(0, db().syncDao().pendingCount(vaultId))
        assertFalse("the other vault's lock must never be taken from inside an owned transaction", SyncVaultLocks.forVault(otherVault).isLocked)
    }

    @Test fun nested_transaction_for_other_database_is_rejected() = runBlocking {
        val app = awaitContainer()
        vault(app, "t2-other-db")
        val other = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val otherId = "sub_t2_other_db_${System.nanoTime()}"
        try {
            val outcome = runCatching {
                app.repository.runInTransaction<Unit> {
                    withCoordinatedTransaction(other, "vault_t2_other_db") {
                        other.subscriptionDao().upsert(
                            com.subtrack.data.db.SubscriptionEntity(
                                id = otherId, name = "OTHER-DB", categoryId = "other", billingCycleType = "MONTHLY",
                                anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000,
                                status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
                            ),
                        )
                    }
                }
            }
            assertTrue(
                "a nested transaction for another database must fail closed, got ${outcome.exceptionOrNull()}",
                outcome.exceptionOrNull() is NestedTransactionScopeException,
            )
            assertNull("the other database must not be written", other.subscriptionDao().findById(otherId))
        } finally {
            other.close()
        }
    }

    // ---------------------------------------------------------------- real reminder flows

    @Test fun muted_reminder_sweep_retires_node_and_still_delivers_others() = runBlocking {
        val app = awaitContainer()
        vault(app, "t2-sweep")
        val today = today()
        val clock = eveningClock(today)
        val mutedId = "sub_t2_sweep_muted_${System.nanoTime()}"
        val normalId = "sub_t2_sweep_normal_${System.nanoTime()}"
        app.repository.upsert(sub(mutedId, "MUTED-SWEEP", charge = today, muted = today))
        app.repository.upsert(sub(normalId, "NORMAL-SWEEP", charge = today))
        for (id in listOf(mutedId, normalId)) {
            db().reminderDao().insertAll(
                listOf(
                    ScheduledReminderEntity(
                        subscriptionId = id, kind = "ONSET", offsetDays = 0,
                        chargeDateEpochDay = today.toEpochDay().toInt(),
                        fireAtUtcMillis = millisOf(today, LocalTime.of(9, 0)),
                        createdAtUtcMillis = clock.nowMillis() - 3_600_000L,
                    ),
                ),
            )
        }
        val mutedRow = db().reminderDao().pendingFor(mutedId).single().id
        val notifier = CountingNotifier()
        val reminder = reminderService(app, clock, notifier)

        val completed = withTimeoutOrNull(15_000) { reminder.sweep(); true } ?: false
        assertTrue("sweep must finish with a muted subscription that still has PENDING nodes", completed)
        assertEquals("the muted node must not be delivered by the sweep", 0, notifier.count(mutedId))
        assertTrue("the muted node must be retired", db().reminderDao().byId(mutedRow)?.state != "PENDING")
        assertTrue("other subscriptions must still be delivered by the same sweep", notifier.count(normalId) >= 1)
    }

    @Test fun muted_legacy_snooze_delivery_clears_compat_mute_and_delivers() = runBlocking {
        val app = awaitContainer()
        vault(app, "t2-legacy-snooze")
        val today = today()
        val clock = eveningClock(today)
        val charge = today.minusDays(2)
        val muted = today.minusDays(1)
        val id = "sub_t2_legacy_${System.nanoTime()}"
        app.repository.upsert(sub(id, "LEGACY-SNOOZE", charge = charge, muted = muted))
        // Legacy shape: a SNOOZE row requested on the muted day that fires the next morning. It is what upgrades
        // from older builds left behind; the compat branch must resolve it inside the delivery transaction.
        db().reminderDao().insertAll(
            listOf(
                ScheduledReminderEntity(
                    subscriptionId = id, kind = "SNOOZE", offsetDays = 0,
                    chargeDateEpochDay = charge.toEpochDay().toInt(),
                    fireAtUtcMillis = millisOf(today, LocalTime.of(9, 0)),
                    createdAtUtcMillis = millisOf(muted, LocalTime.of(8, 0)),
                ),
            ),
        )
        val rowId = db().reminderDao().pendingFor(id).single().id
        val notifier = CountingNotifier()
        val reminder = reminderService(app, clock, notifier)

        val completed = withTimeoutOrNull(10_000) { reminder.deliverExact(rowId); true } ?: false
        assertTrue("the legacy-SNOOZE compatibility branch must finish instead of self-locking", completed)
        assertNull("the compatibility path clears the legacy mute", app.repository.get(id)?.mutedUntil)
        assertEquals("the resolved snooze must be delivered exactly once", 1, notifier.count(id))
        assertEquals("FIRED", db().reminderDao().byId(rowId)?.state)
    }

    // ---------------------------------------------------------------- T1 ledger rollback retained

    @Test fun coordinated_dispatcher_batch_failure_rolls_back_everything() = runBlocking {
        val app = awaitContainer()
        val vaultId = vault(app, "t2-t1-ledger")
        val id = "sub_t1_ledger_${System.nanoTime()}"
        val failed = runCatching {
            app.repository.runInTransaction<Unit> {
                app.repository.upsert(sub(id, "ROLLBACK"))
                withContext(Dispatchers.Default) {
                    withContext(Dispatchers.IO) {
                        app.repository.recordPayment(id, today(), 1000L)
                    }
                }
                error("injected outer business failure")
            }
        }
        assertTrue(failed.isFailure)
        assertNull(app.repository.get(id))
        assertTrue(app.repository.paymentsOf(id).isEmpty())
        assertEquals(0, db().syncDao().pendingCount(vaultId))
        assertTrue(db().syncHeadDao().heads(vaultId, "subscription", id).isEmpty())
    }
}
