package com.subtrack

import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.sync.SyncProductService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1/P3/P4/P6: disabled-period edits are persisted per entity with a stable operation identity; publishing is an
 * explicit, retryable batch whose completion markers survive outbox acknowledgement; active edits are never included.
 */
class DisabledPublishDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val setupToken get() = com.subtrack.SyncE2EDeviceTest.setupTokenForMatrix()

    private suspend fun awaitContainer(): AppContainer {
        repeat(100) {
            runCatching { (context.applicationContext as SubTrackApp).container }.getOrNull()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        error("container not initialised")
    }

    private fun sub(id: String, name: String) = Subscription(
        id = id, name = name, categoryId = "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.of(2026, 9, 29), nextChargeDate = LocalDate.of(2026, 11, 1), priceMinor = 1000L,
    )

    private suspend fun freshVault(app: AppContainer): SyncProductService {
        val service = app.syncService
        service.setEndpoint("http://127.0.0.1:18450")
        service.createVault(setupToken, "device-disabled")
        service.disable()
        return service
    }

    @Test fun publish_covers_all_three_types_and_clears_preview() = runBlocking {
        val app = awaitContainer()
        val service = freshVault(app)
        val stamp = System.nanoTime()
        val subId = "sub_disabled_all_$stamp"
        app.repository.upsert(sub(subId, "DISABLED-EDIT"))
        app.repository.recordPayment(subId, LocalDate.of(2026, 10, 1), 1000L)
        service.upsertCategory(com.subtrack.data.db.CategoryEntity("cat_dis_all_$stamp", "Edited", "tag", 1, false, 99))
        val preview = service.disabledEditPreview()
        assertTrue((preview["subscription"] ?: 0) >= 1)
        assertTrue("payment must be previewed too", (preview["payment"] ?: 0) >= 1)
        assertTrue("category must be previewed too", (preview["category"] ?: 0) >= 1)
        val published = service.publishDisabledEdits()
        assertTrue((published["subscription"] ?: 0) >= 1)
        assertTrue((published["payment"] ?: 0) >= 1)
        assertTrue((published["category"] ?: 0) >= 1)
        assertTrue("published edits must be queued", service.status().pendingUploads >= 3)
        assertTrue("preview must be empty after publishing the frozen batch", service.disabledEditPreview().isEmpty())
    }

    @Test fun repeated_disable_preserves_unpublished_category_edit() = runBlocking {
        val app = awaitContainer()
        val service = freshVault(app)
        val stamp = System.nanoTime()
        service.upsertCategory(com.subtrack.data.db.CategoryEntity("cat_repeat_$stamp", "Kept", "tag", 1, false, 99))
        assertEquals(1, service.disabledEditPreview()["category"] ?: 0)
        service.disable()   // re-disable must not reset any baseline
        assertEquals("re-disable must not swallow the pending category edit", 1, service.disabledEditPreview()["category"] ?: 0)
        service.reenable()
        service.disable()
        assertEquals(1, service.disabledEditPreview()["category"] ?: 0)
    }

    @Test fun active_edit_after_publish_is_not_a_disabled_edit() = runBlocking {
        val app = awaitContainer()
        val service = freshVault(app)
        val stamp = System.nanoTime()
        val subId = "sub_active_after_$stamp"
        app.repository.upsert(sub(subId, "DISABLED"))
        assertTrue(service.publishDisabledEdits().isNotEmpty())
        assertTrue(service.disabledEditPreview().isEmpty())
        service.reenable()
        // Normal edit through the ACTIVE gateway must never be reported as a disabled-period change.
        app.repository.upsert(sub(subId, "ACTIVE-EDIT"))
        assertTrue("active edits must not enter the disabled preview", service.disabledEditPreview().isEmpty())
    }

    @Test fun acknowledged_partial_publish_can_be_retried_without_duplicates() = runBlocking {
        val app = awaitContainer()
        val service = freshVault(app)
        val db = SubTrackDatabase.get(context)
        val stamp = System.nanoTime()
        val a = "sub_p1_a_$stamp"
        val b = "sub_p1_b_$stamp"
        app.repository.upsert(sub(a, "A"))
        app.repository.upsert(sub(b, "B"))
        // Fail exactly the SECOND operation of this batch (identified by its stable opId, so pre-existing rows from other
        // tests cannot influence the injection).
        val vault = requireNotNull(service.activeVault)
        val secondOpId = db.disabledEditDao().pendingRows(vault).sortedBy { it.seq }.map { it.opId }.distinct()[1]
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER p1_fail_second BEFORE INSERT ON sync_outbox " +
                "WHEN NEW.operationId = '$secondOpId' BEGIN SELECT RAISE(ABORT, 'injected'); END;",
        )
        val firstAttempt = runCatching { service.publishDisabledEdits() }
        assertNotNull("the injected failure must abort the batch", firstAttempt.exceptionOrNull())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER p1_fail_second")
        assertEquals("one entity must be queued, the other still pending", 1, service.status().pendingUploads)
        assertEquals("one pending row must remain in the preview", 1, service.disabledEditPreview().values.sum())
        // Upload the successfully queued row and acknowledge it (deletes the outbox row).
        service.reenable()
        service.syncNow()
        assertEquals(0, service.status().pendingUploads)
        // Retry the remaining entity: it must complete WITHOUT re-encoding the acknowledged operation.
        service.disable()
        val retried = service.publishDisabledEdits()
        assertEquals(1, retried.values.sum())
        assertEquals(1, service.status().pendingUploads)
        assertTrue(service.disabledEditPreview().isEmpty())
        service.reenable()
        service.syncNow()
        assertEquals(0, service.status().pendingUploads)
        assertEquals(0, service.status().unresolvedConflicts)
    }
}
