package com.subtrack.network

import com.subtrack.data.settings.SettingsState
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.NetworkSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.FxAmounts
import com.subtrack.widget.widgetInputs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * N2 guard for a flagged risk: the PRODUCTION widget pipeline function [widgetInputs] (used by AppContainer) must carry
 * the online high-precision rates (effectiveFxRates → FxRateTable.preciseRates), not silently drop them to the legacy
 * integer Map. JPY 10,000 must be 422.02, not 400.
 */
class WidgetPipelinePrecisionTest {
    private val payload = """{"base":"EUR","date":"2026-10-28","rates":{"CNY":7.4217,"JPY":175.86,"USD":1.1133}}"""
    private val date = LocalDate.of(2026, 10, 28)
    private fun jpy() = Subscription(
        "widget-jpy", "JPY", "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = date, nextChargeDate = date, priceMinor = 1_000_000L, currency = "JPY",
    )

    @Test fun production_widget_pipeline_carries_online_precision() = runBlocking {
        val q = FxCodec.decode(payload)
        val settings = AppSettings(
            multiCurrencyEnabled = true,
            network = NetworkSettings(fxEnabled = true, autoFxRates = q.rates, autoFxPreciseRates = q.preciseRates),
        )
        val input = widgetInputs(flowOf(listOf(jpy())), flowOf<SettingsState>(SettingsState.Loaded(settings))).first()
        assertTrue(input.currency.enabled)
        assertTrue("pipeline must expose online precise rates", FxAmounts.hasOnline(input.currency.rates))
        assertEquals(42202L to 506428L, input.amounts())
    }
}
