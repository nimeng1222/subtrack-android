package com.subtrack

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

/** Verify the official Compose rule under the existing strict instrumentation protocol. */
class ComposeRuleSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compose_rule_executes_under_protocol_runner() {
        compose.setContent { Box(Modifier.testTag("compose-smoke")) }
        compose.onNodeWithTag("compose-smoke").assertExists()
    }
}
