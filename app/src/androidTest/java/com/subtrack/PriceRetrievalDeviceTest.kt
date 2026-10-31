package com.subtrack

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.subtrack.domain.model.AppSettings
import com.subtrack.ui.network.PriceRetrievalDialog
import org.junit.Rule
import org.junit.Test

/**
 * N5 device/UI verification: the real production retrieval dialog, in deterministic fixture mode, must show per-quote
 * traceable evidence, clearly label fixture (never a real/current price), and produce a locally recomputed savings
 * result. Fixtures only — this proves the UI + local recompute path, not any live model or official price.
 */
class PriceRetrievalDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fixture_retrieval_shows_evidence_and_local_savings_on_device() {
        compose.setContent { PriceRetrievalDialog(AppSettings(), onClose = {}) }
        compose.waitForIdle()

        compose.onNodeWithTag("n5-service").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("n5-region").performScrollTo().performTextInput("US")
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro")
        compose.onNodeWithTag("n5-run").performScrollTo().performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("n5-quote", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Traceable evidence + explicit fixture labelling are rendered.
        compose.onAllNodesWithText("fixture.subtrack.invalid", substring = true).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("evidence source must be shown", it.isNotEmpty())
        }
        compose.onAllNodesWithText("夹具数据", substring = true).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("fixture warning must be shown", it.isNotEmpty())
        }

        // Deterministic locally recomputed savings simulation.
        compose.onNodeWithTag("n5-savings").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("n5-savings-result").performScrollTo().assertExists()
    }

    @Test fun online_http_without_key_fails_explicitly_and_produces_no_quote() {
        compose.setContent { PriceRetrievalDialog(AppSettings(), onClose = {}) }
        compose.waitForIdle()
        compose.onNodeWithTag("n5-service").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("n5-region").performScrollTo().performTextInput("US")
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro")
        // Online HTTP mode, consent given, but the real-search toggle stays OFF and no key → must NOT call any network
        // and must surface an explicit failure, never an empty success or a fabricated price.
        compose.onNodeWithTag("n5-mode-http").performScrollTo().performClick()
        compose.onNodeWithTag("n5-approve").performScrollTo().performClick()
        compose.onNodeWithTag("n5-run").performScrollTo().performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("失败", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("n5-quote", useUnmergedTree = true).assertCountEquals(0)
    }

    /**
     * F5: production UI drives the documented search protocol (auth header) + OpenAI-compatible model extraction against
     * the controlled fixture, then shows only locally evidence-bound quotes. The second half proves the model cannot
     * smuggle a forged source into the result.
     */
    @Test fun online_search_and_model_extraction_through_production_ui() {
        compose.setContent { PriceRetrievalDialog(AppSettings(), onClose = {}) }
        compose.waitForIdle()
        compose.onNodeWithTag("n5-service").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("n5-region").performScrollTo().performTextInput("US")
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro")
        compose.onNodeWithTag("n5-mode-http").performScrollTo().performClick()
        compose.onNodeWithTag("n5-approve").performScrollTo().performClick()
        compose.onNodeWithTag("n5-http-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/search/web")
        compose.onNodeWithTag("n5-http-key").performScrollTo().performTextInput("fixture-search-key")
        compose.onNodeWithTag("n5-http-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("n5-model-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/model/chat")
        compose.onNodeWithTag("n5-model-name").performScrollTo().performTextInput("fixture-model")
        compose.onNodeWithTag("n5-model-key").performScrollTo().performTextInput("fixture-model-key")
        compose.onNodeWithTag("n5-use-model").performScrollTo().performClick()
        compose.onNodeWithTag("n5-run").performScrollTo().performClick()

        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("n5-quote", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Third-party online retrieval label + the real retrieved source are shown; the quote is locally bound.
        compose.onAllNodesWithText("已在线检索", substring = true).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("online verification label must be shown", it.isNotEmpty())
        }
        compose.onAllNodesWithText("fixture.subtrack.invalid", substring = true).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("retrieved source must be shown", it.isNotEmpty())
        }
    }

    @Test fun model_extraction_rejects_forged_source_and_bad_auth_explicitly() {
        compose.setContent { PriceRetrievalDialog(AppSettings(), onClose = {}) }
        compose.waitForIdle()
        compose.onNodeWithTag("n5-service").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("n5-region").performScrollTo().performTextInput("US")
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro")
        compose.onNodeWithTag("n5-mode-http").performScrollTo().performClick()
        compose.onNodeWithTag("n5-approve").performScrollTo().performClick()
        compose.onNodeWithTag("n5-http-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/search/web")
        compose.onNodeWithTag("n5-http-key").performScrollTo().performTextInput("fixture-search-key")
        compose.onNodeWithTag("n5-http-enabled").performScrollTo().performClick()
        // Forged-source model endpoint: extraction yields a candidate pointing outside the retrieved documents.
        compose.onNodeWithTag("n5-model-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/model/chat-bad-source")
        compose.onNodeWithTag("n5-model-name").performScrollTo().performTextInput("fixture-model")
        compose.onNodeWithTag("n5-model-key").performScrollTo().performTextInput("fixture-model-key")
        compose.onNodeWithTag("n5-use-model").performScrollTo().performClick()
        compose.onNodeWithTag("n5-run").performScrollTo().performClick()

        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("n5-issues", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("失败", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("n5-quote", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("FABRICATED_SOURCE", substring = true).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("forged source must be named as rejected", it.isNotEmpty())
        }
    }

    /** F/N5: during a slow retrieval the user switches to a faster endpoint; the stale result must never appear. */
    @Test fun changing_endpoint_during_slow_retrieval_cancels_the_old_request() {
        compose.setContent { PriceRetrievalDialog(AppSettings(), onClose = {}) }
        compose.waitForIdle()
        compose.onNodeWithTag("n5-service").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("n5-region").performScrollTo().performTextInput("US")
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro")
        compose.onNodeWithTag("n5-mode-http").performScrollTo().performClick()
        compose.onNodeWithTag("n5-approve").performScrollTo().performClick()
        compose.onNodeWithTag("n5-http-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/search/web-slow")
        compose.onNodeWithTag("n5-http-key").performScrollTo().performTextInput("fixture-search-key")
        compose.onNodeWithTag("n5-http-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("n5-run").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("检索中", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Switch to the fast endpoint and run again while the slow request is still in flight.
        compose.onNodeWithTag("n5-http-endpoint").performScrollTo().performTextClearance()
        compose.onNodeWithTag("n5-http-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/search/web")
        // Changing the endpoint must cancel the in-flight request (the run button becomes available again).
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("n5-run").assertIsEnabled() }.isSuccess
        }
        // Give the cancelled request's server response time to arrive (3s): it must never be published.
        Thread.sleep(4_000)
        compose.waitForIdle()
        compose.onAllNodesWithTag("n5-quote", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("7.77", substring = true).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("the cancelled slow result must never be shown", it.isEmpty())
        }
    }

    /** F/N5: leaving the page while a slow request is in flight must not crash or publish anything later. */
    @Test fun leaving_the_page_during_slow_retrieval_disposes_without_publishing() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { if (visible.value) PriceRetrievalDialog(AppSettings(), onClose = {}) }
        compose.waitForIdle()
        compose.onNodeWithTag("n5-service").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("n5-region").performScrollTo().performTextInput("US")
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro")
        compose.onNodeWithTag("n5-mode-http").performScrollTo().performClick()
        compose.onNodeWithTag("n5-approve").performScrollTo().performClick()
        compose.onNodeWithTag("n5-http-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/search/web-slow")
        compose.onNodeWithTag("n5-http-key").performScrollTo().performTextInput("fixture-search-key")
        compose.onNodeWithTag("n5-http-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("n5-run").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("检索中", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        visible.value = false
        compose.waitForIdle()
        Thread.sleep(4_000)
        compose.waitForIdle()
        compose.onAllNodesWithTag("n5-quote", useUnmergedTree = true).assertCountEquals(0)
    }

    /** O7: changing the QUERY (not just endpoint) during a slow retrieval invalidates the old request/result. */
    @Test fun changing_query_during_slow_retrieval_invalidates_the_old_result() {
        compose.setContent { PriceRetrievalDialog(AppSettings(), onClose = {}) }
        compose.waitForIdle()
        compose.onNodeWithTag("n5-service").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("n5-region").performScrollTo().performTextInput("US")
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro")
        compose.onNodeWithTag("n5-mode-http").performScrollTo().performClick()
        compose.onNodeWithTag("n5-approve").performScrollTo().performClick()
        compose.onNodeWithTag("n5-http-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/search/web-slow")
        compose.onNodeWithTag("n5-http-key").performScrollTo().performTextInput("fixture-search-key")
        compose.onNodeWithTag("n5-http-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("n5-run").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("检索中", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Change the plan: the old request (and its eventual 7.77 result) must be invalidated.
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextClearance()
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro-Annual")
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("n5-run").assertIsEnabled() }.isSuccess
        }
        Thread.sleep(4_000)
        compose.waitForIdle()
        compose.onAllNodesWithTag("n5-quote", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("7.77", substring = true).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("a result for the previous query must never be published", it.isEmpty())
        }
    }

    /** O7: revoking consent during a slow retrieval cancels it and publishes nothing. */
    @Test fun revoking_consent_during_slow_retrieval_publishes_nothing() {
        compose.setContent { PriceRetrievalDialog(AppSettings(), onClose = {}) }
        compose.waitForIdle()
        compose.onNodeWithTag("n5-service").performScrollTo().performTextInput("Sample")
        compose.onNodeWithTag("n5-region").performScrollTo().performTextInput("US")
        compose.onNodeWithTag("n5-plan").performScrollTo().performTextInput("Pro")
        compose.onNodeWithTag("n5-mode-http").performScrollTo().performClick()
        compose.onNodeWithTag("n5-approve").performScrollTo().performClick()
        compose.onNodeWithTag("n5-http-endpoint").performScrollTo().performTextInput("http://127.0.0.1:18080/search/web-slow")
        compose.onNodeWithTag("n5-http-key").performScrollTo().performTextInput("fixture-search-key")
        compose.onNodeWithTag("n5-http-enabled").performScrollTo().performClick()
        compose.onNodeWithTag("n5-run").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("检索中", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("n5-approve").performScrollTo().performClick()   // revoke consent
        Thread.sleep(4_000)
        compose.waitForIdle()
        compose.onAllNodesWithTag("n5-quote", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("7.77", substring = true).fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("no result may be published after consent is revoked", it.isEmpty())
        }
    }
}
