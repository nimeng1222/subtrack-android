package com.subtrack

import com.subtrack.data.settings.SettingsStore
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.BudgetWatch
import com.subtrack.domain.stats.CurrencyConverter
import com.subtrack.domain.stats.MonthlyCashflow
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.network.FxCodec
import com.subtrack.widget.WidgetCurrency
import com.subtrack.widget.WidgetInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * N2 device-level matrix: the SAME high-precision online rate persisted through the real [SettingsStore] must drive
 * flow conversion, stats, expansion breakdown, monthly cashflow (budget), and the widget identically (aggregate before
 * round → JPY 10,000 → CNY 422.02, not 400). Manual override wins; disabling/absent FX yields explicit missing (never
 * 1 or 0). It exercises the shared calculators together on device, not just a WidgetInput pure function.
 */
class FxSettingsDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val payload = """{"base":"EUR","date":"2026-10-28","rates":{"CNY":7.4217,"JPY":175.86,"USD":1.1133}}"""
    private val date = LocalDate.of(2026, 10, 28)

    private fun jpy() = Subscription(
        "fx-jpy", "JPY 会员", "other", cycle = BillingCycleType.MONTHLY,
        anchorDate = date, nextChargeDate = date, priceMinor = 1_000_000L, currency = "JPY",
    )

    @Test fun online_precision_persists_and_drives_flow_stats_breakdown_budget_widget_consistently() = runBlocking {
        val store = SettingsStore(context)
        val original = store.observe().first()
        val snapshot = FxCodec.decode(payload)
        try {
            store.updateNetwork {
                it.copy(
                    fxEnabled = true, autoFxPreciseRates = snapshot.preciseRates, autoFxRates = snapshot.rates,
                    fxDate = "2026-10-28", fxSource = "frankfurter", fxBase = "EUR",
                )
            }
            // Device persistence: a fresh store instance reads the same precise rates back from disk.
            val reloaded = SettingsStore(context).observe().first()
            assertEquals(snapshot.preciseRates, reloaded.network.autoFxPreciseRates)
            val rates = reloaded.effectiveFxRates

            // 流水折算
            assertEquals(42202L, CurrencyConverter.toCnyMinor(1_000_000L, "JPY", rates))
            // 统计
            val stats = StatsEngine.snapshot(listOf(jpy()), date, 42202L, 1, 0L, 0, null, rates)
            assertEquals(506428L, stats.forecastAnnualMinor)
            // 展开明细一致
            assertEquals(stats.forecastAnnualMinor, StatsEngine.annualBreakdown(listOf(jpy()), rates).totalMinor)
            // 本月将扣（预算/现金流口径）
            val due = MonthlyCashflow.dueThisMonthMinor(listOf(jpy()), date, rates)
            assertEquals(42202L, due)
            // launcher 小组件
            assertEquals(42202L to 506428L, WidgetInput(listOf(jpy()), WidgetCurrency(true, rates)).amounts())
            // 预算使用同一现金流口径
            assertEquals(42202L, BudgetWatch.status(100_000L, 0L, due)!!.spentMinor)

            // 旧手动汇率优先于在线高精度
            val current = SettingsStore(context).observe().first()
            store.update(current.copy(fxRates = mapOf("JPY" to 5L)))
            assertEquals(50000L, CurrencyConverter.toCnyMinor(1_000_000L, "JPY", SettingsStore(context).observe().first().effectiveFxRates))

            // 清除手动汇率后（仍启用在线）回到高精度值；再关闭 FX → 缺失（不当作 1 或 0）。
            store.update(SettingsStore(context).observe().first().copy(fxRates = emptyMap()))
            assertEquals(42202L, CurrencyConverter.toCnyMinor(1_000_000L, "JPY", SettingsStore(context).observe().first().effectiveFxRates))
            store.updateNetwork { it.copy(fxEnabled = false) }
            assertNull(CurrencyConverter.toCnyMinor(1_000_000L, "JPY", SettingsStore(context).observe().first().effectiveFxRates))
        } finally {
            store.update(original)
        }
    }
}
