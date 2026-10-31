package com.subtrack

import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Q1–Q4: disabled-period publication lifecycle (real frozen session, recoverable state machine, frozen content). */
class DisabledPublishLifecycleDeviceTest {
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

    private suspend fun freshDisabledVault(app: AppContainer): SyncProductService {
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-lifecycle")
        service.disable()
        return service
    }

    @Test fun session_replaced_while_publish_waits_must_reject_stale_publication() = runBlocking {
        val app = awaitContainer()
        val service = freshDisabledVault(app)
        val subId = "sub_q1_${System.nanoTime()}"
        app.repository.upsert(sub(subId, "Q1"))
        val vault = requireNotNull(service.activeVault)
        val lock = SyncVaultLocks.forVault(vault)
        lock.lock()
        val scope = CoroutineScope(Dispatchers.Default)
        val publication = scope.async { runCatching { service.publishDisabledEdits() } }
        delay(300)
        service.reenable()          // replaces the frozen session identity
        lock.unlock()
        val result = publication.await()
        scope.cancel()
        assertTrue("stale publication must be rejected: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is SyncSessionLostException)
        assertEquals("nothing may be queued by the stale task", 0, db().syncDao().pendingCount(vault))
        assertEquals("confirmed content must stay pending", 1, service.disabledEditPreview()["subscription"] ?: 0)
    }

    @Test fun queued_but_unpublished_window_recovers_after_ack_and_restart() = runBlocking {
        val app = awaitContainer()
        val service = freshDisabledVault(app)
        val subId = "sub_q2_${System.nanoTime()}"
        app.repository.upsert(sub(subId, "Q2"))
        val vault = requireNotNull(service.activeVault)
        val row = db().disabledEditDao().pendingRows(vault).first { it.entityId == subId }
        service.publishDisabledEdits()
        // Simulate the crash window: outbox+queuedAt committed, publishedAt missing.
        db().openHelper.writableDatabase.execSQL(
            "UPDATE sync_disabled_edit SET publishedAt=NULL WHERE seq=${row.seq}",
        )
        service.reenable()
        service.syncNow()          // upload + ack deletes the outbox row
        service.disable()
        val recreated = SyncProductService(context, db(), app.syncRuntime)  // product service after restart
        recreated.publishDisabledEdits().let { /* recovery only, may be empty */ }
        assertTrue("preview must be cleared after recovery", recreated.disabledEditPreview().isEmpty())
        assertEquals("no duplicate operation may be queued", 0, db().syncDao().pendingCount(vault))
    }

    @Test fun changed_confirmation_is_rejected_instead_of_overwriting_with_old_or_new_content() = runBlocking {
        val app = awaitContainer()
        val service = freshDisabledVault(app)
        val subId = "sub_q3_${System.nanoTime()}"
        app.repository.upsert(sub(subId, "CONFIRMED-OLD"))
        val vault = requireNotNull(service.activeVault)
        val lock = SyncVaultLocks.forVault(vault); lock.lock()
        val scope = CoroutineScope(Dispatchers.Default)
        val token = service.disabledPreviewBatchToken()
        val publication = scope.async { runCatching { service.publishDisabledEdits(token) } }
        delay(300)
        app.repository.upsert(sub(subId, "LATER-ACTIVE"))   // content changes while the batch waits for the lock
        lock.unlock()
        val result = publication.await()
        scope.cancel()
        // R3: neither the stale snapshot nor the later value may be silently published under the old confirmation.
        assertTrue("stale confirmation must be rejected: ${result.exceptionOrNull()}", result.isFailure)
        assertEquals("nothing may be queued", 0, db().syncDao().pendingCount(vault))
        assertEquals("the current content must remain confirmable", 1, service.disabledEditPreview()["subscription"] ?: 0)
        val fresh = service.previewDisabledBatch()
        val published = service.publishDisabledEdits(fresh.token)
        assertEquals(1, published["subscription"] ?: 0)
    }

    @Test fun legacy_prefs_unpublished_edit_migrates_into_preview_without_uploading() = runBlocking {
        val app = awaitContainer()
        val service = freshDisabledVault(app)
        val vault = requireNotNull(service.activeVault)
        val subId = "sub_q4_${System.nanoTime()}"
        // A schema-4 style ledger row written directly (no recorder), plus the legacy prefs markers.
        db().subscriptionDao().upsert(
            com.subtrack.data.db.SubscriptionEntity(
                id = subId, name = "LEGACY", categoryId = "other", billingCycleType = "MONTHLY",
                anchorDateEpochDay = 1, nextChargeDateEpochDay = 2, priceMinor = 1000, status = "ACTIVE",
                createdAtUtcMillis = 1, updatedAtUtcMillis = 2000,
            ),
        )
        context.getSharedPreferences("subtrack-sync-product", android.content.Context.MODE_PRIVATE).edit()
            .putLong("publishFloorMillis", 1000L)
            .putString("categorySnapshot", null)
            .remove("legacyDisabledEditMigrated")
            .commit()
        val recreated = SyncProductService(context, db(), app.syncRuntime)
        recreated.migrateLegacyDisabledState()
        assertTrue("the legacy row must be migrated into the persistent edit log",
            db().disabledEditDao().pendingFor(vault, "subscription", subId).isNotEmpty())
        assertTrue("legacy unpublished edit must appear in the new preview",
            (recreated.disabledEditPreview()["subscription"] ?: 0) >= 1)
        assertEquals("migration must never upload by itself", 0, db().syncDao().pendingCount(vault))
        val afterFirst = db().disabledEditDao().pendingRows(vault).size
        recreated.migrateLegacyDisabledState()   // idempotent
        assertEquals("migration must be idempotent", afterFirst, db().disabledEditDao().pendingRows(vault).size)
    }
}
