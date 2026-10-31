package com.subtrack.network

import com.subtrack.domain.model.*
import com.subtrack.domain.stats.CurrencyConverter
import com.subtrack.domain.stats.MonthlyCashflow
import com.subtrack.widget.WidgetCurrency
import com.subtrack.widget.WidgetInput
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FxPrecisionTest {
    private val payload = """{"base":"EUR","date":"2026-10-28","rates":{"CNY":7.4217,"JPY":175.86,"USD":1.1133}}"""
    @Test fun frankfurter_jpy_does_not_round_rate_to_four_cents() {
        val q = FxCodec.decode(payload)
        val s = AppSettings(multiCurrencyEnabled = true, network = NetworkSettings(fxEnabled = true, autoFxPreciseRates = q.preciseRates))
        assertEquals(42202L, CurrencyConverter.toCnyMinor(1_000_000L, "JPY", s.effectiveFxRates))
        val date = LocalDate.of(2026, 10, 28)
        val sub = Subscription("precise", "JPY", "other", cycle = BillingCycleType.MONTHLY,
            anchorDate = date, nextChargeDate = date, priceMinor = 1_000_000L, currency = "JPY")
        assertEquals(42202L, MonthlyCashflow.dueThisMonthMinor(listOf(sub), date, s.effectiveFxRates))
        // Annual amount is calculated before rounding the monthly display amount.
        assertEquals(42202L to 506428L, WidgetInput(listOf(sub), WidgetCurrency(true, s.effectiveFxRates)).amounts())
        val snapshot = com.subtrack.domain.stats.StatsEngine.snapshot(listOf(sub), date, 42202L, 1, 0L, 0, null, s.effectiveFxRates)
        assertEquals(506428L, snapshot.forecastAnnualMinor)
        assertEquals(snapshot.forecastAnnualMinor, com.subtrack.domain.stats.StatsEngine.annualBreakdown(listOf(sub), s.effectiveFxRates).totalMinor)
        assertEquals(1_000_000L, sub.priceMinor)
    }
    @Test fun manual_overrides_and_old_online_cache_requires_refresh() {
        val q = FxCodec.decode(payload)
        val s = AppSettings(fxRates = mapOf("JPY" to 5L), network = NetworkSettings(fxEnabled = true,
            autoFxRates = q.rates, autoFxPreciseRates = q.preciseRates))
        assertEquals(50000L, CurrencyConverter.toCnyMinor(1_000_000L, "JPY", s.effectiveFxRates))
        val old = s.copy(fxRates = emptyMap(), network = s.network.copy(autoFxPreciseRates = emptyMap()))
        assertNull(CurrencyConverter.toCnyMinor(1_000_000L, "JPY", old.effectiveFxRates))
        assertNull(CurrencyConverter.toCnyMinor(1_000_000L, "JPY", s.copy(fxRates = emptyMap(), network = s.network.copy(fxEnabled = false)).effectiveFxRates))
    }
    @Test fun rejects_missing_cny_and_extreme_derived_rate() {
        for (raw in listOf("""{"base":"EUR","date":"2026-10-28","rates":{"USD":1}}""",
            """{"base":"EUR","date":"2026-10-28","rates":{"CNY":1,"USD":1e-100}}""")) {
            assertTrue(runCatching { FxCodec.decode(raw) }.isFailure)
        }
    }
    @Test fun cny_base_inverse_and_missing_currency() {
        val q = FxCodec.decode("""{"base":"CNY","date":"2026-10-28","rates":{"USD":0.2}}""")
        val s = AppSettings(network = NetworkSettings(fxEnabled = true, autoFxPreciseRates = q.preciseRates))
        assertEquals(500L, CurrencyConverter.toCnyMinor(100L, "USD", s.effectiveFxRates))
        assertNull(CurrencyConverter.toCnyMinor(100L, "JPY", s.effectiveFxRates))
    }
    @Test fun auto_refresh_waits_across_restart_and_clock_reversal() {
        val day = AutoFxRefresh.INTERVAL_MS
        assertEquals(0L, AutoFxRefresh.remaining(day * 2, 0))
        assertEquals(day - 1000, AutoFxRefresh.remaining(day + 1000, day))
        assertEquals(0L, AutoFxRefresh.remaining(day * 2, day))
        assertEquals(day, AutoFxRefresh.remaining(day - 1, day))
    }

    // 四种公共汇率源的响应形状都要能解析成同一个 CNY 定点口径。
    @Test fun all_public_sources_decode_to_same_cny_scale() {
        // 取能整除的报价，断言就是精确值（1/0.125 = 8 元）。
        val v6 = """{"result":"success","base_code":"CNY","time_last_update_unix":1793404951,"rates":{"CNY":1,"USD":0.125,"JPY":25,"HKD":1.25,"EUR":0.2,"GBP":0.5}}"""
        val v4 = """{"provider":"x","base":"CNY","date":"2026-10-31","rates":{"CNY":1,"USD":0.125,"JPY":25,"HKD":1.25,"EUR":0.2,"GBP":0.5}}"""
        val fawaz = """{"date":"2026-10-30","cny":{"usd":0.125,"jpy":25,"hkd":1.25,"eur":0.2,"gbp":0.5}}"""
        val frank = """{"amount":1.0,"base":"CNY","date":"2026-10-30","rates":{"USD":0.125,"JPY":25,"HKD":1.25,"EUR":0.2,"GBP":0.5}}"""
        for (raw in listOf(v6, v4, fawaz, frank)) {
            val q = FxCodec.decode(raw)
            assertEquals("CNY", q.base)
            assertEquals(8_000_000_000L, q.preciseRates.getValue("USD"))
        }
        assertEquals("2026-10-30", FxCodec.decode(fawaz).date)
        assertEquals("2026-10-31", FxCodec.decode(v6).date)
    }

    @Test fun unknown_source_label_falls_back_without_lying() {
        assertEquals("Frankfurter（欧洲央行参考价）", FxSources.labelOf(FxSources.DEFAULT[0].url))
        assertEquals("自定义来源", FxSources.labelOf("https://example.com/fx"))
    }
}
