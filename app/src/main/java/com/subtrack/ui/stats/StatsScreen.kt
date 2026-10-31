package com.subtrack.ui.stats

import com.subtrack.core.money.sumMoney
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.core.money.Money
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.ui.Categories
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.BottomFade
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import com.subtrack.ui.components.SectionHeader
import com.subtrack.ui.home.FloatingTabBar
import com.subtrack.ui.home.Title
import java.time.LocalDate
import kotlin.math.min
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Separator
import com.subtrack.ui.theme.Success
import com.subtrack.ui.theme.Warning

/**
 * 统计页（P0-4）。AC-35 是这一页的灵魂：每个数字都要能点开看公式与条目清单。
 * 所以三个大数字全部可点，不是静态文本。
 */
@Composable
fun StatsScreen(
    stats: StatsEngine.Snapshot,
    subscriptions: List<Subscription>,
    today: LocalDate,
    insight: StatsEngine.Insight?,
    monthlyTrend: List<com.subtrack.domain.stats.BillingCalendar.MonthTotal>,
    /** L2 增强：全部可为空/空列表，为空时对应卡片不渲染。 */
    budget: com.subtrack.domain.stats.BudgetWatch.Status?,
    priceChanges: List<com.subtrack.domain.stats.PriceWatch.Change>,
    healthFindings: List<com.subtrack.domain.stats.SubscriptionHealth.Finding>,
    missingRates: List<String>,
    /** 多币种折算口径：null = 未启用；明细与统计卡必须同口径。 */
    fxRates: Map<String, Long>? = null,
    onOpenSubscription: (String) -> Unit,
    onOpenCurrencySettings: () -> Unit,
    onOpenBreakdown: (StatsEngine.Breakdown) -> Unit,
    onOpenCalendar: () -> Unit,
    onSelectTab: (Int) -> Unit,
) {
    val total = stats.categories.sumMoney { it.annualMinor }.coerceAtLeast(1L)

    AmbientBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
            ) {
                item { Title("统计") }
                item {
                    Spacer(Modifier.height(12.dp))
                    SummaryCard(stats, subscriptions, today, fxRates, onOpenBreakdown)
                }
                // 只有真正能比较时（近 90 天有流水）才出现；以前这里放一张
                // "实际口径待启用"的空卡，既不能点也没有信息，用户只会问"这有什么用"。
                insight?.let { card ->
                    item {
                        Spacer(Modifier.height(12.dp))
                        InsightCard(card)
                    }
                }
                if (stats.oneTimeCount > 0) {
                    item { Spacer(Modifier.height(12.dp)); OneTimeCard(stats) }
                }
                // L2 增强卡：有数据才渲染，没有就整块省略
                budget?.let { b ->
                    item { Spacer(Modifier.height(12.dp)); BudgetCard(b) {} }
                }
                if (priceChanges.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        PriceChangeCard(priceChanges, onOpenSubscription)
                    }
                }
                if (healthFindings.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        HealthCard(healthFindings, onOpenSubscription)
                    }
                }
                if (missingRates.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        MissingRateHint(missingRates, onOpenCurrencySettings)
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    TrendCard(monthlyTrend)
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    CalendarEntry(onOpenCalendar)
                }
                item {
                    Spacer(Modifier.height(18.dp))
                    SectionHeader("分类占比")
                    Spacer(Modifier.height(12.dp))
                    CategoryCard(stats, total)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            BottomFade(Modifier.align(Alignment.BottomCenter))
            FloatingTabBar(
                selected = 1,
                onSelect = onSelectTab,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun SummaryCard(
    stats: StatsEngine.Snapshot,
    subscriptions: List<Subscription>,
    today: LocalDate,
    fxRates: Map<String, Long>?,
    onOpenBreakdown: (StatsEngine.Breakdown) -> Unit,
) {
    GlassCard(corner = 24.dp, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), alpha = 0.76f, elevation = 20.dp) {
        Text("本月将扣", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
        Spacer(Modifier.height(8.dp))
        AmountText(Money.format(stats.dueThisMonthMinor), size = 34, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${stats.dueThisMonthCount} 笔 · 现金流口径",
                style = TextStyle(fontSize = 13.sp, color = InkSecondary),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "看明细",
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenBreakdown(StatsEngine.dueBreakdown(subscriptions, today)) }                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Medium),
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Separator))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onOpenBreakdown(StatsEngine.monthlyBreakdown(subscriptions, fxRates)) },
            ) {
                Text("预计月均 · 承诺口径", style = TextStyle(fontSize = 11.sp, color = InkSecondary))
                Spacer(Modifier.height(4.dp))
                AmountText(Money.format(stats.forecastMonthlyMinor), size = 19, weight = FontWeight.SemiBold)
            }
            Box(Modifier.width(0.5.dp).height(34.dp).background(Separator))
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text("近90天实际月均", style = TextStyle(fontSize = 11.sp, color = InkSecondary))
                Spacer(Modifier.height(4.dp))
                if (stats.actualMonthlyMinor == null) {
                    // AC-36a：覆盖不足 90 天不展示外推数字，只给占位说明
                    Text(
                        "记录满 90 天后可核对",
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = InkSecondary),
                    )
                } else {
                    AmountText(Money.format(stats.actualMonthlyMinor), size = 19, weight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenBreakdown(StatsEngine.annualBreakdown(subscriptions, fxRates)) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("年度总支出", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
            Spacer(Modifier.weight(1f))
            AmountText(Money.format(stats.forecastAnnualMinor), size = 17, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text("明细", style = TextStyle(fontSize = 12.sp, color = Accent))
        }
    }
}

@Composable
private fun InsightCard(insight: StatsEngine.Insight) {
    GlassCard(corner = 18.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), alpha = 0.72f) {
        Text(
            insight.headline,
            style = TextStyle(
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (insight.suggestAdd) Warning else Success,
            ),
        )
        Spacer(Modifier.height(2.dp))
        Text(insight.detail, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = InkSecondary))
    }
}

@Composable
private fun OneTimeCard(stats: StatsEngine.Snapshot) {
    GlassCard(corner = 18.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), alpha = 0.72f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("一次性支出", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                Spacer(Modifier.height(2.dp))
                Text(
                    "${stats.oneTimeCount} 项买断，不参与摊销",
                    style = TextStyle(fontSize = 12.sp, color = InkSecondary),
                )
            }
            AmountText(Money.format(stats.oneTimeTotalMinor), size = 17, weight = FontWeight.SemiBold)
        }
    }
}
