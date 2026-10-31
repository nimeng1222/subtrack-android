package com.subtrack

import androidx.room.withTransaction
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.SyncProductService
import com.subtrack.sync.SyncSessionLostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** S1–S4: unified transaction/lock coordination, real startup recovery, restored binding generation, full digest. */
class StartupDeadlockDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val setupToken get() = com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix()
    private fun db() = SubTrackDatabase.get(context)

    private suspend fun awaitContainer(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            delay(50)
        }
        error("container not initialised")
    }

    private fun sub(id: String, name: String, note: String? = null) = Subscription(
        id = id, name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.of(2026, 9, 29), nextChargeDate = LocalDate.of(2026, 11, 1), priceMinor = 1000L,
        note = note,
    )

    private suspend fun vault(app: AppContainer): SyncProductService {
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-startup")
        service.disable()
        return service
    }

    @Test fun outer_repository_transaction_and_sync_must_not_deadlock() = runBlocking {
        val app = awaitContainer()
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-deadlock")
        val id = "sub_s1_deadlock_${System.nanoTime()}"
        val scope = CoroutineScope(Dispatchers.Default)
        withTimeout(20_000) {
            val sync = scope.async { runCatching { service.syncNow() } }
            delay(100)   // let sync acquire the vault lock and enter its Room transaction
            val outer = scope.async {
                app.repository.runInTransaction {
                    app.repository.upsert(sub(id, "OUTER-TX"))
                }
            }
            outer.await()
            sync.await()
        }
        scope.cancel()
        assertEquals("the edit committed inside the outer transaction", "OUTER-TX", app.repository.get(id)?.name)
    }

    @Test fun confirmed_before_enqueue_recovers_through_real_startup() = runBlocking {
        val app = awaitContainer()
        val service = vault(app)
        val vaultId = requireNotNull(service.activeVault)
        val id = "sub_s2_${System.nanoTime()}"
        app.repository.upsert(sub(id, "CONFIRMED"))
        val row = db().disabledEditDao().pendingRows(vaultId).first { it.entityId == id }
        db().disabledEditDao().markConfirmed(row.seq, System.currentTimeMillis())
        assertEquals("confirmed rows are hidden until recovery", 0, service.disabledEditPreview()["subscription"] ?: 0)
        // REAL production startup chain: a new AppContainer runs migration + recovery and releases the readiness gate.
        val fresh = AppContainer(context)
        withTimeout(15_000) { fresh.awaitSyncReady() }
        assertEquals("startup must return the interrupted confirmation to the user", 1,
            fresh.syncService.disabledEditPreview()["subscription"] ?: 0)
        assertEquals(0, db().syncDao().pendingCount(vaultId))
    }

    @Test fun restored_offline_gateway_validates_generation() = runBlocking {
        val app = awaitContainer()
        val service = vault(app)
        val vaultId = requireNotNull(service.activeVault)
        // A new service instance restores the disabled binding from persisted state (the startup path).
        val restored = SyncProductService(context, db(), app.syncRuntime)
        val id = "sub_s3_${System.nanoTime()}"
        val error = runCatching {
            app.syncRuntime.subscription(
                SubscriptionEntity(
                    id = id, name = "R", categoryId = "other", billingCycleType = "MONTHLY",
                    anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE",
                    createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
                ),
                false,
            ) { restored.reenable() }
        }.exceptionOrNull()
        assertTrue("restored offline gateway must reject after a binding change: $error", error is SyncSessionLostException)
        assertNull(db().subscriptionDao().findById(id))
        assertTrue(db().disabledEditDao().pendingFor(vaultId, "subscription", id).isEmpty())
    }

    @Test fun token_covers_note_even_when_timestamp_is_unchanged() = runBlocking {
        val app = awaitContainer()
        val service = vault(app)
        val vaultId = requireNotNull(service.activeVault)
        val id = "sub_s4_${System.nanoTime()}"
        app.repository.upsert(sub(id, "NOTE", note = "A"))
        val before = service.previewDisabledBatch()
        val original = db().subscriptionDao().findById(id)!!
        // Same updatedAt/createdAt tick, only the note changes: the token must still change.
        db().subscriptionDao().upsert(original.copy(note = "B"))
        val after = service.previewDisabledBatch()
        assertNotEquals("note must be part of the token", before.token, after.token)
        val rejected = runCatching { service.publishDisabledEdits(before.token) }
        assertTrue("old token must be rejected", rejected.isFailure)
        assertEquals(0, db().syncDao().pendingCount(vaultId))
        val published = service.publishDisabledEdits(after.token)
        assertEquals(1, published["subscription"] ?: 0)
    }

    @Test fun batch_import_and_renewal_transaction_does_not_deadlock_with_sync() = runBlocking {
        val app = awaitContainer()
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-deadlock-batch")
        val id = "sub_s1_batch_${System.nanoTime()}"
        val scope = CoroutineScope(Dispatchers.Default)
        withTimeout(20_000) {
            val sync = scope.async { runCatching { service.syncNow() } }
            delay(100)
            // Import/renewal style batch: several ledger writes + payment inside ONE outer Room transaction.
            val batch = scope.async {
                app.repository.runInTransaction {
                    app.repository.upsert(sub(id, "IMPORTED"))
                    app.repository.recordPayment(id, LocalDate.of(2026, 10, 1), 1000L)
                }
            }
            batch.await()
            sync.await()
        }
        scope.cancel()
        assertEquals("IMPORTED", app.repository.get(id)?.name)
    }

    @Test fun outer_transaction_after_dispatcher_switch_must_not_deadlock() = runBlocking {
        val app = awaitContainer()
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-t1")
        val id = "sub_t1_cross_${System.nanoTime()}"
        val scope = CoroutineScope(Dispatchers.Default)
        val inOuterTx = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        withTimeout(20_000) {
            val outer = scope.async {
                app.repository.runInTransaction<Unit> {
                    inOuterTx.countDown()
                    // T1: cross-dispatcher continuation keeps transaction ownership; the gateway must hop back to the
                    // transaction thread instead of taking the vault lock that syncNow is holding.
                    withContext(Dispatchers.IO) {
                        releaseWrite.await(15, java.util.concurrent.TimeUnit.SECONDS)
                        app.repository.upsert(sub(id, "CROSS-THREAD"))
                    }
                }
            }
            inOuterTx.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val sync = scope.async { runCatching { service.syncNow() } }
            delay(200)   // let syncNow acquire the vault lock and wait for the Room transaction
            releaseWrite.countDown()
            outer.await()
            sync.await()
        }
        scope.cancel()
        assertEquals("CROSS-THREAD", app.repository.get(id)?.name)
    }

    @Test fun nested_dispatcher_transactions_and_batch_writes_stay_atomic() = runBlocking {
        val app = awaitContainer()
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-t1-batch")
        val id = "sub_t1_batch_${System.nanoTime()}"
        withTimeout(20_000) {
            app.repository.runInTransaction<Unit> {
                withContext(Dispatchers.Default) {
                    app.repository.upsert(sub(id, "NESTED"))
                    withContext(Dispatchers.IO) {
                        app.repository.recordPayment(id, LocalDate.of(2026, 10, 1), 1000L)
                    }
                }
            }
        }
        assertEquals("NESTED", app.repository.get(id)?.name)
        assertEquals("payment committed in the same coordinated transaction", 1, app.repository.paymentsOf(id).size)
    }

    @Test fun session_lost_inside_coordinated_transaction_rolls_back_everything() = runBlocking {
        val app = awaitContainer()
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-t1-rollback")
        val id = "sub_t1_rollback_${System.nanoTime()}"
        val entity = com.subtrack.data.db.SubscriptionEntity(
            id = id, name = "WILL-ROLLBACK", categoryId = "other", billingCycleType = "MONTHLY",
            anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE",
            createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
        )
        val error = runCatching {
            app.repository.runInTransaction<Unit> {
                // Invalidate the session INSIDE the ledger mutation of the coordinated transaction.
                app.syncRuntime.subscription(entity, false) { service.switchEndpoint("http://127.0.0.1:18451") }
            }
        }.exceptionOrNull()
        assertTrue("session loss must abort the coordinated transaction: $error", error is com.subtrack.sync.SyncSessionLostException)
        assertNull("ledger row must be rolled back", app.repository.get(id))
        service.switchEndpoint("http://127.0.0.1:18450")
        Unit
    }
}
