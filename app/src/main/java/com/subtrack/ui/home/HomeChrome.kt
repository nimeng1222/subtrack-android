package com.subtrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.R
import com.subtrack.ui.components.nativeShadow
import java.time.LocalDate
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SubTrackTheme

/** 浮动胶囊 Tab：白色 74% + 选中项白胶囊。 */
@Composable
fun FloatingTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        "订阅" to R.drawable.ic_receipt,
        "统计" to R.drawable.ic_chart,
        "AI" to R.drawable.ic_sparkle,
        "设置" to R.drawable.ic_gear,
    )
    Row(
        modifier = modifier
            .width(356.dp)
            .height(60.dp)
            .nativeShadow(corner = 30.dp, blur = 22.dp, dy = 10.dp, color = 0x1F0F172A)
            .clip(RoundedCornerShape(30.dp))
            .background(if (SubTrackTheme.colors.isDark) SubTrackTheme.colors.surfaceSecondary.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.74f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, (label, icon) ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .then(
                        if (active) Modifier
                            .nativeShadow(corner = 22.dp, blur = 10.dp, dy = 2.dp, color = 0x140F172A)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (SubTrackTheme.colors.isDark) SubTrackTheme.colors.segmentBg else Color.White.copy(alpha = 0.96f))
                        else Modifier,
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painterResource(icon),
                        null,
                        Modifier.size(22.dp),
                        tint = if (active) Accent else InkSecondary,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        label,
                        style = TextStyle(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = if (active) Accent else InkSecondary,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }
    }
}

internal fun cycleLabel(type: com.subtrack.domain.model.BillingCycleType): String = when (type) {
    com.subtrack.domain.model.BillingCycleType.WEEKLY -> "周付"
    com.subtrack.domain.model.BillingCycleType.BIWEEKLY -> "双周付"
    com.subtrack.domain.model.BillingCycleType.FOUR_WEEKLY -> "四周付"
    com.subtrack.domain.model.BillingCycleType.MONTHLY -> "月付"
    com.subtrack.domain.model.BillingCycleType.QUARTERLY -> "季付"
    com.subtrack.domain.model.BillingCycleType.SEMIANNUAL -> "半年付"
    com.subtrack.domain.model.BillingCycleType.YEARLY -> "年付"
    com.subtrack.domain.model.BillingCycleType.CUSTOM_DAYS -> "自定义"
    com.subtrack.domain.model.BillingCycleType.ONE_TIME -> "一次性"
}

internal fun relativeLabel(date: LocalDate?, today: LocalDate): String {
    if (date == null) return "未设置"
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> "已过期 ${-days} 天"
        days == 0L -> "今天"
        days == 1L -> "明天"
        days in 2..7 -> "${days} 天后"
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("M月d日"))
    }
}
