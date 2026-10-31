package com.subtrack

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.ui.editor.EditorSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stable replacement for the flaky accessibility-based disabled-assertion test.
 *
 * It mounts the real production [EditorSheet], enters invalid custom day counts through a stable testTag, and
 * verifies with the official Compose test rule that (a) neither the top "保存" nor the bottom "保存订阅" can commit
 * an invalid draft and (b) the bottom save reports a genuine disabled semantics state. A valid count then re-enables
 * the bottom save, proving the input actually takes effect (the assertion is not trivially always-disabled).
 */
class EditorValidationComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compose_editor_rejects_invalid_days_from_both_saves_and_recalculate() {
        val today = LocalDate.of(2026, 10, 27)
        val saved = AtomicInteger(0)
        val initial = Subscription(
            id = UUID.randomUUID().toString(),
            name = "EDITOR VALIDATION",
            categoryId = "other",
            cycle = BillingCycleType.CUSTOM_DAYS,
            customCycleDays = 30,
            anchorDate = today.minusDays(12),
            nextChargeDate = today.plusDays(12),
            priceMinor = 1000L,
        )
        compose.setContent {
            EditorSheet(today, initial, onDismiss = {}, onSave = { saved.incrementAndGet() })
        }
        compose.waitForIdle()

        for (invalid in listOf("", "0", "3651")) {
            compose.onNodeWithTag("editor-custom-days").performScrollTo().performTextReplacement(invalid)
            compose.waitForIdle()
            compose.onNodeWithTag("editor-recalc").performScrollTo().performClick()
            compose.waitForIdle()
            // Top save validates before committing; an invalid draft must never reach onSave.
            compose.onNodeWithTag("editor-save-top").performClick()
            compose.waitForIdle()
            assertEquals("invalid='$invalid' must not save", 0, saved.get())
            // Bottom save must expose a real disabled state for the same illegal value.
            compose.onNodeWithTag("editor-save-bottom").assertIsNotEnabled()
        }

        // Control: a legal day count re-enables the bottom save, proving input and recomposition are in sync.
        compose.onNodeWithTag("editor-custom-days").performScrollTo().performTextReplacement("30")
        compose.waitForIdle()
        compose.onNodeWithTag("editor-recalc").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("editor-save-bottom").assertIsEnabled()
        assertEquals(0, saved.get())
    }
}
