package com.subtrack

import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** R1/R2/R3: transactional admission, recoverable confirmation states, and content-bound preview tokens. */
class PublishProtocolDeviceTest {
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

    private fun sub(id: String, name: String) = Subscription(
        id = id, name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.of(2026, 9, 29), nextChargeDate = LocalDate.of(2026, 11, 1), priceMinor = 1000L,
    )

    private fun entity(id: String) = SubscriptionEntity(
        id = id, name = "R", categoryId = "other", billingCycleType = "MONTHLY", anchorDateEpochDay = 1,
        nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE", createdAtUtcMillis = 1, updatedAtUtcMillis = 1,
    )

    private suspend fun activeVault(app: AppContainer): SyncProductService {
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-protocol")
        return service
    }

    @Test fun active_gateway_rolls_back_when_session_invalidated_during_mutation() = runBlocking {
        val app = awaitContainer()
        val service = activeVault(app)
        val id = "sub_r1_active_${System.nanoTime()}"
        val error = runCatching {
            app.syncRuntime.subscription(entity(id), false) {
                service.switchEndpoint("http://127.0.0.1:18451")   // real product entry, invalidates the session
            }
        }.exceptionOrNull()
        assertTrue("end-of-transaction admission must abort the commit: $error", error is SyncSessionLostException)
        assertNull("ledger row must be rolled back", db().subscriptionDao().findById(id))
        assertEquals("outbox must be rolled back", 0, db().syncDao().pendingCount(requireNotNull(service.activeVault)))
        service.switchEndpoint("http://127.0.0.1:18450")
        Unit
    }

    @Test fun offline_gateway_rolls_back_when_binding_changes_during_mutation() = runBlocking {
        val app = awaitContainer()
        val service = activeVault(app)
        service.disable()
        val vault = requireNotNull(service.activeVault)
        val id = "sub_r1_offline_${System.nanoTime()}"
        val error = runCatching {
            app.syncRuntime.subscription(entity(id), false) {
                service.reenable()   // bumps the binding generation inside the mutate body
            }
        }.exceptionOrNull()
        assertTrue("offline admission must abort the commit: $error", error is SyncSessionLostException)
        assertNull("offline ledger row must be rolled back", db().subscriptionDao().findById(id))
        assertTrue("no disabled-edit row may survive", db().disabledEditDao().pendingFor(vault, "subscription", id).isEmpty())
        Unit
    }

    @Test fun confirmed_but_not_queued_is_recoverable_after_restart() = runBlocking {
        val app = awaitContainer()
        val service = activeVault(app)
        service.disable()
        val vault = requireNotNull(service.activeVault)
        val id = "sub_r2_confirmed_${System.nanoTime()}"
        app.repository.upsert(sub(id, "CONFIRMED"))
        val row = db().disabledEditDao().pendingRows(vault).first { it.entityId == id }
        db().disabledEditDao().markConfirmed(row.seq, System.currentTimeMillis())
        assertTrue("confirmed rows are not shown as confirmable", service.disabledEditPreview().isEmpty())
        val recreated = SyncProductService(context, db(), app.syncRuntime)
        recreated.recoverInterruptedPublish()
        assertEquals("a frozen-but-unqueued confirmation must return to the user", 1,
            recreated.disabledEditPreview()["subscription"] ?: 0)
        assertEquals(0, db().syncDao().pendingCount(vault))
        Unit
    }

    @Test fun cancelled_publication_returns_rows_to_pending_confirmation() = runBlocking {
        val app = awaitContainer()
        val service = activeVault(app)
        service.disable()
        val vault = requireNotNull(service.activeVault)
        val id = "sub_r2_cancel_${System.nanoTime()}"
        app.repository.upsert(sub(id, "CANCEL"))
        val lock = SyncVaultLocks.forVault(vault)
        lock.lock()
        val scope = CoroutineScope(Dispatchers.Default)
        val publication = scope.async { runCatching { service.publishDisabledEdits() } }
        delay(300)
        publication.cancelAndJoin()   // cancel while waiting for the vault lock
        lock.unlock()
        scope.cancel()
        assertEquals("nothing may be queued", 0, db().syncDao().pendingCount(vault))
        assertEquals("rows must be confirmable again", 1, service.disabledEditPreview()["subscription"] ?: 0)
        Unit
    }

    @Test fun preview_token_must_detect_changed_entity_content() = runBlocking {
        val app = awaitContainer()
        val service = activeVault(app)
        service.disable()
        val vault = requireNotNull(service.activeVault)
        val id = "sub_r3_token_${System.nanoTime()}"
        app.repository.upsert(sub(id, "A"))
        val preview = service.previewDisabledBatch()
        assertEquals(1, preview.counts["subscription"] ?: 0)
        assertTrue(preview.token.isNotBlank())
        // Same entity changed after the preview: the old token must be rejected and nothing enqueued.
        app.repository.upsert(sub(id, "B"))
        val rejected = runCatching { service.publishDisabledEdits(preview.token) }
        assertTrue("stale token must be rejected: ${rejected.exceptionOrNull()}", rejected.isFailure)
        assertEquals("nothing may be queued by a stale confirmation", 0, db().syncDao().pendingCount(vault))
        assertEquals("content must stay confirmable", 1, service.disabledEditPreview()["subscription"] ?: 0)
        // Re-preview "B" and publish successfully.
        val fresh = service.previewDisabledBatch()
        assertNotEquals("token must change with content", preview.token, fresh.token)
        val published = service.publishDisabledEdits(fresh.token)
        assertEquals(1, published["subscription"] ?: 0)
        assertEquals(1, db().syncDao().pendingCount(vault))
        Unit
    }
}
