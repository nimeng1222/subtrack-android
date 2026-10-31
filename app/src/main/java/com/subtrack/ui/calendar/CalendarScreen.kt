package com.subtrack.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.core.money.Money
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.BillingCalendar
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.BottomFade
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import com.subtrack.ui.components.SectionHeader
import com.subtrack.ui.components.nativeShadow
import com.subtrack.ui.home.FloatingTabBar
import com.subtrack.ui.home.Title
import com.subtrack.ui.home.cycleLabel
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SubTrackTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 账单日历（v1.1）。
 *
 * 设计取舍：不画"12 个月热度大图"，而是「一个月历 + 下方 12 个月条形」。
 * 理由：用户真正的问题是"这个月哪几天要扣钱"，月历能直接回答；
 * 12 个月总览放在下面做趋势暗示，两者不抢注意力。
 *
 * 热力着色用**相对比例**（当月最大值归一）而不是绝对金额：
 * 订阅单价跨度可能 6 元到 999 元，按绝对值大多数格子都会落进最低档，图就没信息了。
 */
@Composable
fun CalendarScreen(
    subscriptions: List<Subscription>,
    today: LocalDate,
    onOpen: (Subscription) -> Unit,
    onSelectTab: (Int) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.of(today.year, today.monthValue)) }
    val bills = remember(subscriptions, month) { BillingCalendar.month(subscriptions, month) }
    var selected by remember(month) { mutableStateOf<LocalDate?>(null) }

    val yearTotals = remember(subscriptions, month.year) {
        BillingCalendar.monthlyTotals(subscriptions, month.year)
    }
    val maxDay = bills.values.maxOfOrNull { it.totalMinor } ?: 0L

    AmbientBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 130.dp),
            ) {
                item { Title("账单日历") }
                item {
                    Spacer(Modifier.height(10.dp))
                    MonthSwitcher(
                        month = month,
                        onPrev = { month = month.minusMonths(1); selected = null },
                        onNext = { month = month.plusMonths(1); selected = null },
                        onToday = { month = YearMonth.of(today.year, today.monthValue); selected = null },
                    )
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    MonthGrid(
                        month = month,
                        bills = bills,
                        today = today,
                        maxDay = maxDay,
                        selected = selected,
                        onSelect = { selected = if (selected == it) null else it },
                    )
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    MonthSummary(month, bills)
                }

                // 选中某天时展开当日账单（就地处理，不用跳页）
                selected?.let { date ->
                    item {
                        Spacer(Modifier.height(14.dp))
                        DayDetail(
                            date = date,
                            subs = bills[date]?.subscriptions.orEmpty(),
                            onOpen = onOpen,
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(22.dp))
                    SectionHeader("${month.year} 年月度分布")
                    Spacer(Modifier.height(10.dp))
                    YearBars(month.year, yearTotals, month.monthValue) { m ->
                        month = YearMonth.of(month.year, m)
                        selected = null
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
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
private fun MonthSwitcher(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${month.year} 年 ${month.monthValue} 月",
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-0.3).sp),
        )
        Spacer(Modifier.weight(1f))
        NavButton("<", onPrev)
        Spacer(Modifier.width(8.dp))
        Text(
            "今天",
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(SegmentBg)
                .clickable(onClick = onToday)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            style = TextStyle(fontSize = 12.sp, color = InkSecondary),
        )
        Spacer(Modifier.width(8.dp))
        NavButton(">", onNext)
    }
}

@Composable
private fun NavButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(32.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SegmentBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontSize = 15.sp, color = Ink, fontWeight = FontWeight.Medium))
    }
}
