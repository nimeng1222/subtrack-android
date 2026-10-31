package com.subtrack.readiness

import com.subtrack.data.settings.*
import com.subtrack.domain.model.*
import com.subtrack.ui.settings.*
import com.subtrack.widget.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadinessTest {
    @Test fun battery_routes_fall_back_with_feedback_and_never_request_direct_exemption() {
        val attempted = mutableListOf<BatteryDestination>()
        val result = routeBatterySettings {
            attempted += it
            if (it != BatteryDestination.APP_DETAILS) error("OEM action unavailable")
        }
        assertEquals(listOf(BatteryDestination.OPTIMIZATION_LIST, BatteryDestination.APP_DETAILS), attempted)
        assertEquals(BatteryDestination.APP_DETAILS, result.destination)
        assertTrue(result.message.contains("电池"))
        val unavailable = routeBatterySettings { throw SecurityException("not allowed") }
        assertNull(unavailable.destination)
        assertTrue(unavailable.message.contains("无法打开"))
    }

    @Test fun settings_only_refreshes_wait_for_real_settings_and_read_the_published_value() = runTest {
        val loaded = LoadedSettings()
        val subs = MutableStateFlow(emptyList<Subscription>())
        val observed = mutableListOf<WidgetInput>()
        val published = mutableListOf<WidgetCurrency>()
        val job = backgroundScope.launch {
            refreshWidgets(widgetInputs(subs, loaded.state)) { input ->
                observed += input
                published += loaded.current().let { WidgetCurrency(it.multiCurrencyEnabled, it.fxRates) }
            }
        }
        runCurrent(); assertTrue(observed.isEmpty())
        val first = AppSettings(multiCurrencyEnabled = true, fxRates = mapOf("USD" to 100L))
        loaded.publish(first); runCurrent()
        loaded.publish(first.copy(fxRates = mapOf("USD" to 200L))); runCurrent()
        loaded.publish(first.copy(multiCurrencyEnabled = false)); runCurrent()
        assertEquals(3, observed.size)
        assertEquals(observed.map { it.currency }, published)
        loaded.publish(first.copy(multiCurrencyEnabled = false, appLockEnabled = true)); runCurrent()
        assertEquals("Unrelated settings need not refresh amounts", 3, observed.size)
        job.cancel()
    }

    @Test fun subscription_changes_and_currency_switches_keep_the_accepted_amount_rules() = runTest {
        val date = LocalDate.of(2026, 10, 28)
        val usd = Subscription("usd", "USD probe", "other", cycle = BillingCycleType.MONTHLY,
            anchorDate = date, nextChargeDate = date.plusMonths(1), priceMinor = 1000, currency = "USD")
        val loaded = LoadedSettings(); val subs = MutableStateFlow(listOf(usd))
        val observed = mutableListOf<WidgetInput>()
        backgroundScope.launch { refreshWidgets(widgetInputs(subs, loaded.state)) { observed += it } }
        loaded.publish(AppSettings(multiCurrencyEnabled = true, fxRates = mapOf("USD" to 100L))); runCurrent()
        assertEquals(1000L to 12000L, observed.last().amounts())
        loaded.publish(AppSettings(multiCurrencyEnabled = true, fxRates = mapOf("USD" to 200L))); runCurrent()
        assertEquals(2000L to 24000L, observed.last().amounts())
        loaded.publish(AppSettings(multiCurrencyEnabled = true)); runCurrent()
        assertEquals(0L to 0L, observed.last().amounts()) // Missing rate is excluded, not treated as CNY.
        loaded.publish(AppSettings(multiCurrencyEnabled = false)); runCurrent()
        assertEquals(1000L to 12000L, observed.last().amounts())
        subs.value = emptyList(); runCurrent()
        assertEquals(0L to 0L, observed.last().amounts())
        assertEquals(5, observed.size)
    }
}
