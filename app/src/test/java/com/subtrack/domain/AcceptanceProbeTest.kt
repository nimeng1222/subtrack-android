package com.subtrack.domain
import com.subtrack.domain.cycle.CycleEngine
import com.subtrack.domain.model.*
import com.subtrack.domain.stats.*
import com.subtrack.data.backup.BackupCodec
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AcceptanceProbeTest {
    private val today = LocalDate.of(2026,10,27)
    @Test fun editor_safe_days_null_must_not_crash_cycle_selection() {
        val days: Int? = null
        val safeDays = days?.takeIf { Limits.isValidCustomCycleDays(it) }
        for (invalid in listOf(null, 0, 3651)) {
            val previous = today.plusDays(12)
            val result = com.subtrack.ui.editor.recalculateEditorDate(today, BillingCycleType.CUSTOM_DAYS, invalid, today, previous)
            assertEquals(previous, result.date)
            assertNotNull(result.error)
            assertNotNull(com.subtrack.ui.editor.validate("test", 100L, today, today, BillingCycleType.CUSTOM_DAYS, invalid))
        }
        val monthly = com.subtrack.ui.editor.recalculateEditorDate(today, BillingCycleType.MONTHLY, safeDays, today, today)
        assertNull(monthly.error)
        assertNotNull(com.subtrack.ui.editor.recalculateEditorDate(today, BillingCycleType.CUSTOM_DAYS, safeDays, today, monthly.date).error)
    }
    @Test fun imported_max_price_and_fx_must_be_safe_for_multiple_subscriptions() {
        val subs = (1..3).map {
            Subscription(id="s$it", name="S$it", categoryId="other",
                cycle=BillingCycleType.CUSTOM_DAYS, customCycleDays=1,
                anchorDate=today, nextChargeDate=today, priceMinor=Limits.MAX_PRICE_MINOR, currency="USD")
        }
        val settings = AppSettings(multiCurrencyEnabled=true, fxRates=mapOf("USD" to Limits.MAX_FX_RATE))
        val parsed = BackupCodec.importBackup(BackupCodec.exportJson(subs, settings=settings))
        CurrencyConverter.annualizedCnyMinorSkipMissing(parsed.subscriptions,parsed.settings!!.fxRates)
    }
    @Test fun imported_legal_values_must_not_wrap_statistics_negative() {
        val subs = (1..3).map {
            Subscription(id="s$it", name="S$it", categoryId="other",
                cycle=BillingCycleType.CUSTOM_DAYS, customCycleDays=1,
                anchorDate=today, nextChargeDate=today, priceMinor=Limits.MAX_PRICE_MINOR, currency="USD")
        }
        val snap = StatsEngine.snapshot(subs,today,0,0,0,0,null,mapOf("USD" to Limits.MAX_FX_RATE))
        assertTrue("Positive prices must not produce a negative forecast", snap.forecastAnnualMinor >= 0L)
    }
    @Test fun manual_rate_editor_must_not_persist_crashing_rate() {
        var updated = AppSettings(multiCurrencyEnabled=true)
        com.subtrack.ui.settings.commitRate("USD", "10000000000000000", updated) { updated = it }
        CurrencyConverter.toCnyMinor(1000L,"USD",updated.fxRates)
    }
}
