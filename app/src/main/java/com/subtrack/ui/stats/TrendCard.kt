package com.subtrack.ui.stats

import com.subtrack.core.money.sumMoney
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.core.money.Money
import com.subtrack.domain.stats.BillingCalendar
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SubTrackTheme

/**
 * 近 6 个月趋势（v1.1）。
 *
 * 用柱状图而不是折线：月度支出是 6 个离散的桶，不是连续函数。
 * 折线会暗示"月中有个中间值"，那是假的。图表按文档 §1.5 自绘，不引图表库。
 */
@Composable
fun TrendCard(
    monthly: List<BillingCalendar.MonthTotal>,
    modifier: Modifier = Modifier,
) {
    val maxTotal = monthly.maxOfOrNull { it.totalMinor } ?: 0L
    val maxCount = monthly.maxOfOrNull { it.count } ?: 0

    GlassCard(modifier = modifier, corner = 22.dp, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "近 6 个月",
                    style = TextStyle(fontSize = 13.sp, color = InkSecondary, fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "按当前订阅推算的月度扣费",
                    style = TextStyle(fontSize = 11.sp, color = InkSecondary),
                )
            }
            val sum = monthly.sumMoney { it.totalMinor }
            val avg = if (monthly.isEmpty()) 0L else if (sum == Long.MAX_VALUE) Long.MAX_VALUE else sum / monthly.size
            Column(horizontalAlignment = Alignment.End) {
                Text("均值", style = TextStyle(fontSize = 11.sp, color = InkSecondary))
                Text(
                    Money.format(avg),
                    style = TextStyle(
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth().height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            monthly.forEachIndexed { index, m ->
                val isCurrent = index == monthly.lastIndex
                val ratio = if (maxTotal <= 0) 0f else m.totalMinor.toFloat() / maxTotal
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        Money.formatCompact(m.totalMinor),
                        style = TextStyle(
                            fontSize = 9.sp,
                            color = if (isCurrent) Accent else InkSecondary,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((8 + 66 * ratio).dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isCurrent) Accent else Accent.copy(alpha = 0.26f)),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "${m.yearMonth.monthValue}月",
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = if (isCurrent) Accent else InkSecondary,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

/** 进入账单日历的入口卡。 */
@Composable
fun CalendarEntry(onClick: () -> Unit) {
    GlassCard(corner = 18.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("账单日历", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                Spacer(Modifier.height(2.dp))
                Text(
                    "按月查看哪天要扣钱，12 个月热度一眼可见",
                    style = TextStyle(fontSize = 12.sp, color = InkSecondary),
                )
            }
            Text("查看", style = TextStyle(fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Medium))
        }
    }
}
