package com.subtrack.domain

import com.subtrack.core.money.*
import com.subtrack.domain.model.*
import com.subtrack.domain.stats.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AmountSafetyTest {
    private val date = LocalDate.of(2026, 10, 27)
    private val subs = (1..3).map { Subscription(id = "$it", name = "$it", categoryId = "$it", cycle = BillingCycleType.CUSTOM_DAYS,
        customCycleDays = 1, anchorDate = date, nextChargeDate = date, priceMinor = Limits.MAX_PRICE_MINOR, currency = "USD") }
    @Test fun maximum_legal_data_retains_exact_monthly_total_and_category_ratios() {
        val fx = mapOf("USD" to Limits.MAX_FX_RATE)
        val stats = StatsEngine.snapshot(subs, date, 0, 0, 0, 0, null, fx)
        assertEquals(Long.MAX_VALUE, stats.forecastAnnualMinor)
        assertTrue(Money.format(stats.forecastAnnualMinor).contains("超出显示范围"))
        assertEquals(912_500_000_000_000_000L, stats.forecastMonthlyMinor)
        assertEquals(stats.forecastMonthlyMinor, StatsEngine.monthlyBreakdown(subs, fx).totalMinor)
        assertEquals(listOf(3333L,3333L,3333L), stats.categories.map { it.basisPoints(stats.forecastAnnualMinor) })
        assertEquals(stats.forecastAnnualMinor, CurrencyConverter.annualizedCnyMinorSkipMissing(subs, fx))
    }
    @Test fun input_never_wraps_or_replaces_previous_rate_on_error() {
        for (raw in listOf("10000000000000000", "18446744073709551616", "1e9999999", "-1", "0", "10000.01")) {
            var settings = AppSettings(fxRates = mapOf("USD" to 720L))
            val error = com.subtrack.ui.settings.commitRate("USD", raw, settings) { settings = it }
            assertNotNull(raw, error)
            assertEquals(720L, settings.fxRates["USD"])
        }
        assertEquals(Limits.MAX_FX_RATE, MoneyInput.rate("10000"))
        assertNull(MoneyInput.price("18446744073709551616"))
        assertEquals(Limits.MAX_PRICE_MINOR, MoneyInput.price("10000000000"))
        assertNull(com.subtrack.ui.editor.validate("free", 0L, date, date))
        assertEquals(Long.MAX_VALUE, BudgetWatch.status(100L, Long.MAX_VALUE - 1, 10)!!.spentMinor)
    }
    @Test fun legacy_out_of_range_rate_is_missing_not_a_crash() {
        val fx = mapOf("USD" to Long.MAX_VALUE)
        assertNull(CurrencyConverter.toCnyMinor(1000, "USD", fx))
        assertEquals(listOf("USD"), CurrencyConverter.missingRates(subs, fx))
    }
}
