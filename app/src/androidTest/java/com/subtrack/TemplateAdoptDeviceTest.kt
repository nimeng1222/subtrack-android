package com.subtrack

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import com.subtrack.core.time.AppClock
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.repository.SubscriptionRepositoryImpl
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.NetworkSettings
import com.subtrack.network.NetworkTransport
import com.subtrack.network.OnlineRepository
import com.subtrack.ui.editor.EditorSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * N1 end-to-end through the real production editor: real HTTP fetch from the local fixture (populates cache with source
 * + fetch time), open the template browser (shows cached source/time), select a template, open details, adopt to the
 * editable draft, fill the unknown price, save → exactly one subscription and zero auto payment records.
 */
class TemplateAdoptDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = Round2Instrumentation.instance.targetContext
    private val base = "http://127.0.0.1:18080"
    private val clock = object : AppClock {
        override fun today() = LocalDate.now()
        override fun nowMillis() = System.currentTimeMillis()
        override fun reminderTime() = LocalTime.of(9, 0)
    }

    @Test fun template_fetch_adopt_unknown_price_save_creates_one_subscription_zero_payments() {
        // 1) Real HTTP fetch from the fixture (proves online fetch + populates cache with source/fetch-time).
        runBlocking { OnlineRepository(context, NetworkTransport(true)).templates("$base/templates") }

        val settings = AppSettings().copy(
            network = NetworkSettings(templatesEnabled = true, templatesBaseUrl = "$base/templates", connectionRevision = 7),
        )
        val db = Room.inMemoryDatabaseBuilder(context, SubTrackDatabase::class.java).build()
        val repo = SubscriptionRepositoryImpl(db.subscriptionDao(), db.paymentRecordDao(), db.categoryDao(), clock)
        val saved = AtomicInteger(0)
        try {
            compose.setContent {
                EditorSheet(
                    today = LocalDate.now(), initial = null, defaultSettings = settings,
                    onDismiss = {}, onSave = { s -> saved.incrementAndGet(); runBlocking { repo.upsert(s) } },
                )
            }
            compose.waitForIdle()

            // 2) Open template browser; cached source + fetch time are shown.
            compose.onNodeWithTag("tpl-open").performScrollTo().performClick()
            compose.waitForIdle()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("模板源", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // 3) Select a template whose price is unknown, open details, adopt to draft.
            compose.onNodeWithTag("tpl-row-github-us-pro").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("tpl-use").performClick()
            compose.waitForIdle()

            // 4) Name/currency prefilled; price left EMPTY (unknown must not become zero).
            compose.onNodeWithTag("editor-name").performScrollTo().assertTextContains("GitHub")
            compose.onNodeWithTag("editor-amount").performScrollTo().assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString(""),
                ),
            )

            // 5) Fill the unknown price manually and save once.
            compose.onNodeWithTag("editor-amount").performScrollTo().performTextInput("12.34")
            compose.waitForIdle()
            // The bottom save footer is a fixed sibling outside the scroll container (always visible); do not scrollTo.
            compose.onNodeWithTag("editor-save-bottom").performClick()
            compose.waitForIdle()

            assertEquals(1, saved.get())
            runBlocking {
                assertEquals(1, db.subscriptionDao().count())
                assertEquals(0, db.paymentRecordDao().all().size)   // no auto-generated payment
                assertEquals(1234L, db.subscriptionDao().allIds().let { db.subscriptionDao().findById(it.single())!!.priceMinor })
                assertEquals("USD", db.subscriptionDao().findById(db.subscriptionDao().allIds().single())!!.currency)
            }
        } finally { db.close() }
    }
}
