package com.subtrack.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.BudgetWatch
import com.subtrack.domain.stats.CurrencyConverter
import com.subtrack.domain.stats.PriceWatch
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.domain.stats.SubscriptionHealth
import java.time.LocalDate

/**
 * L2 四项增强的派生状态。
 *
 * 抽成一个 Composable 工厂而不是写在 MainActivity 里，理由和 BackupLaunchers 一样：
 * 这些计算有 4 个，每个都要读设置 + 订阅 + 流水，堆在导航代码里会让主文件炸掉。
 *
 * **所有输出在功能关闭时都是"空"**——UI 层靠这个保证"关掉就不渲染"，
 * 而不是每个卡片自己再判一次开关。开关判断只在这里做一次。
 */
class EnhancementState(
    val budget: BudgetWatch.Status?,
    val priceChanges: List<PriceWatch.Change>,
    val healthFindings: List<SubscriptionHealth.Finding>,
    val missingRates: List<String>,
    /** 多币种折算后的年化；未启用或缺汇率时为 null。 */
    val convertedAnnualMinor: Long?,
)

@Composable
fun rememberEnhancementState(
    subscriptions: List<Subscription>,
    settings: AppSettings,
    today: LocalDate,
    stats: StatsEngine.Snapshot,
    monthSpentMinor: Long,
    lastPaidAmounts: Map<String, Long>,
): EnhancementState = remember(subscriptions, settings, today, stats, monthSpentMinor, lastPaidAmounts) {
    val budget = if (settings.monthlyBudgetMinor != null) {
        BudgetWatch.status(
            budgetMinor = settings.monthlyBudgetMinor,
            monthSpentMinor = monthSpentMinor,
            monthUpcomingMinor = stats.dueThisMonthMinor,
        )
    } else {
        null
    }

    val priceChanges = if (settings.priceWatchEnabled) {
        PriceWatch.detect(subscriptions, lastPaidAmounts)
    } else {
        emptyList()
    }

    val health = if (settings.healthCheckEnabled) {
        SubscriptionHealth.findings(subscriptions, today, stats.forecastMonthlyMinor)
    } else {
        emptyList()
    }

    val missing = if (settings.multiCurrencyEnabled) {
        CurrencyConverter.missingRates(subscriptions, settings.effectiveFxRates)
    } else {
        emptyList()
    }

    EnhancementState(
        budget = budget,
        priceChanges = priceChanges,
        healthFindings = health,
        missingRates = missing,
        convertedAnnualMinor = if (settings.multiCurrencyEnabled && missing.isEmpty()) {
            CurrencyConverter.annualizedCnyMinor(subscriptions, settings.effectiveFxRates)
        } else {
            null
        },
    )
}

/**
 * 只在"涨价预警"开启时才去查最近支付金额。
 *
 * 关掉时返回空 Map 而不是仍然查库：这是一个会扫全表的查询，
 * 功能没开就不该为它付代价。
 */
@Composable
fun rememberLastPaid(
    container: com.subtrack.AppContainer,
    enabled: Boolean,
    subscriptions: List<Subscription>,
    refreshKey: Int,
): Map<String, Long> {
    val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Map<String, Long>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(enabled, subscriptions, refreshKey) {
        state.value = if (enabled) {
            runCatching { container.repository.lastPaidAmounts() }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
    }
    return state.value
}
