package com.subtrack.ui.calendar

import com.subtrack.core.money.sumMoney
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import com.subtrack.ui.components.nativeShadow
import com.subtrack.ui.home.cycleLabel
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SubTrackTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 日历的视觉部件：月网格、日格、当日明细、年度条形图。 */
@Composable
internal fun MonthGrid(
    month: YearMonth,
    bills: Map<LocalDate, BillingCalendar.DayBill>,
    today: LocalDate,
    maxDay: Long,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    val firstDay = month.atDay(1)
    // 周一为第一列（中文习惯），dayOfWeek.value: 周一=1
    val leading = firstDay.dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    val cells = leading + days
    val rows = (cells + 6) / 7

    GlassCard(corner = 22.dp, padding = PaddingValues(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                Text(
                    w,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontSize = 11.sp, color = InkSecondary),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                for (c in 0 until 7) {
                    val index = r * 7 + c
                    val dayNum = index - leading + 1
                    if (dayNum in 1..days) {
                        val date = month.atDay(dayNum)
                        val bill = bills[date]
                        DayCell(
                            day = dayNum,
                            totalMinor = bill?.totalMinor ?: 0L,
                            heat = BillingCalendar.heatLevel(bill?.totalMinor ?: 0L, maxDay),
                            isToday = date == today,
                            isSelected = date == selected,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(date) },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun DayCell(
    day: Int,
    totalMinor: Long,
    heat: Int,
    isToday: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = SubTrackTheme.colors
    // 热力：从 accent 的 12% 到 100%
    val fill = when {
        isSelected -> colors.accent
        heat == 0 -> Color.Transparent
        else -> colors.accent.copy(alpha = 0.10f + 0.18f * heat)
    }
    val textColor = when {
        isSelected -> Color.White
        heat >= 3 -> Color.White
        else -> Ink
    }
    Box(
        modifier
            .padding(horizontal = 2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(9.dp))
            .background(fill)
            .then(
                if (isToday && !isSelected) {
                    Modifier.nativeShadow(9.dp, blur = 0.dp, dy = 0.dp, color = 0x00000000)
                } else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$day",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = if (isToday || isSelected || heat >= 3) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                ),
            )
            if (totalMinor > 0) {
                Text(
                    Money.formatCompact(totalMinor),
                    style = TextStyle(
                        fontSize = 8.sp,
                        color = if (isSelected) Color.White.copy(alpha = 0.9f) else InkSecondary,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
        if (isToday && !isSelected) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 3.dp)
                    .width(14.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Accent),
            )
        }
    }
}

@Composable
internal fun MonthSummary(month: YearMonth, bills: Map<LocalDate, BillingCalendar.DayBill>) {
    val total = bills.values.sumMoney { it.totalMinor }
    val count = bills.values.sumOf { it.count }
    GlassCard(corner = 18.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本月共扣", style = TextStyle(fontSize = 12.sp, color = InkSecondary))
                Spacer(Modifier.height(3.dp))
                AmountText(Money.format(total), size = 20, weight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${bills.size} 个扣费日", style = TextStyle(fontSize = 12.sp, color = InkSecondary))
                Spacer(Modifier.height(3.dp))
                Text("$count 笔", style = TextStyle(fontSize = 15.sp, color = Ink))
            }
        }
    }
}

@Composable
internal fun DayDetail(date: LocalDate, subs: List<Subscription>, onOpen: (Subscription) -> Unit) {
    val fmt = DateTimeFormatter.ofPattern("M月d日")
    GlassCard(corner = 20.dp, padding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date.format(fmt),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${subs.size} 笔 · ${Money.format(subs.sumMoney { it.priceMinor })}",
                style = TextStyle(fontSize = 12.sp, color = InkSecondary),
            )
        }
        subs.forEachIndexed { i, sub ->
            if (i > 0) InsetDivider(56)
            val category = com.subtrack.ui.Categories.of(sub.categoryId)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(sub) }
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(category.iconRes, category.color, 30.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sub.name, style = TextStyle(fontSize = 15.sp, color = Ink))
                    Text(
                        cycleLabel(sub.cycle),
                        style = TextStyle(fontSize = 11.sp, color = InkSecondary),
                    )
                }
                AmountText(Money.format(sub.priceMinor), size = 15, color = Ink)
            }
        }
    }
}

/** 12 个月条形：当月高亮，点柱切换月份。 */
@Composable
internal fun YearBars(
    year: Int,
    totals: List<Long>,
    activeMonth: Int,
    onPick: (Int) -> Unit,
) {
    val max = totals.maxOrNull() ?: 0L
    GlassCard(corner = 22.dp, padding = PaddingValues(16.dp)) {
        Row(
            Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            totals.forEachIndexed { index, value ->
                val m = index + 1
                val ratio = if (max <= 0) 0f else value.toFloat() / max
                val isActive = m == activeMonth
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onPick(m) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((6 + 78 * ratio).dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (isActive) Accent else Accent.copy(alpha = 0.28f),
                            ),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "$m",
                        style = TextStyle(
                            fontSize = 9.sp,
                            color = if (isActive) Accent else InkSecondary,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}
