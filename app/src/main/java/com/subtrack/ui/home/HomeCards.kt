package com.subtrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.R
import com.subtrack.core.money.Money
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.state.SubscriptionDisplayState
import com.subtrack.ui.Categories
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.ChevronIcon
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import com.subtrack.ui.components.SectionHeader
import com.subtrack.ui.components.nativeShadow
import com.subtrack.ui.theme.Type
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Separator
import com.subtrack.ui.theme.SubTrackTheme
import com.subtrack.ui.theme.Warning

@Composable
internal fun Title(text: String) {
    Text(text, style = Type.largeTitle, color = Ink)
}

@Composable
internal fun TopActions(onBell: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Box {
            Icon(
                painter = painterResource(R.drawable.ic_bell),
                contentDescription = "提醒中心",
                modifier = Modifier.size(23.dp).clickable(onClick = onBell),
                tint = Ink,
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Warning)
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-1).dp),
            )
        }
        Spacer(Modifier.width(22.dp))
        Icon(
            painter = painterResource(R.drawable.ic_plus),
            contentDescription = "新建订阅",
            modifier = Modifier.size(23.dp).clickable(onClick = onAdd),
            tint = Accent,
        )
    }
}

@Composable
internal fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .nativeShadow(corner = 12.dp, blur = 8.dp, dy = 2.dp, color = 0x0A0F172A)
            .clip(RoundedCornerShape(12.dp))
            .background(if (SubTrackTheme.colors.isDark) SubTrackTheme.colors.fieldBg else Color.White.copy(alpha = 0.58f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, Modifier.size(16.dp), tint = InkSecondary)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索订阅", style = TextStyle(fontSize = 16.sp, color = InkSecondary))
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = Ink),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun PermissionBanner(onEnable: () -> Unit) {
    GlassCard(corner = 18.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 13.dp), alpha = 0.74f) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            CategoryBadge(R.drawable.ic_bell, Warning, 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("通知已关闭", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                Spacer(Modifier.height(1.dp))
                Text(
                    "到期不会提醒你，只能打开应用才看得到",
                    style = TextStyle(fontSize = 12.sp, color = InkSecondary),
                )
            }
            Text(
                "去开启",
                modifier = Modifier.clickable(onClick = onEnable).padding(vertical = 4.dp),
                style = TextStyle(fontSize = 14.sp, color = Accent, fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable
internal fun HeroCard(stats: com.subtrack.domain.stats.StatsEngine.Snapshot, today: LocalDate) {
    val monthEnd = today.with(TemporalAdjusters.lastDayOfMonth())
    GlassCard(corner = 24.dp, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), alpha = 0.76f, elevation = 20.dp) {
        Text("本月将扣", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
        Spacer(Modifier.height(8.dp))
        AmountText(Money.format(stats.dueThisMonthMinor), size = 34, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "${stats.dueThisMonthCount} 笔 · 至 ${monthEnd.monthValue}月${monthEnd.dayOfMonth}日" +
                if (stats.oneTimeCount > 0) " · 另有一次性 ${Money.format(stats.oneTimeTotalMinor)}" else "",
            style = TextStyle(fontSize = 13.sp, color = InkSecondary),
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Separator))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("预计月均", style = TextStyle(fontSize = 11.sp, color = InkSecondary))
                Spacer(Modifier.height(4.dp))
                AmountText(Money.format(stats.forecastMonthlyMinor), size = 19, weight = FontWeight.SemiBold)
            }
            Box(Modifier.width(0.5.dp).height(34.dp).background(Separator))
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text("年度总支出", style = TextStyle(fontSize = 11.sp, color = InkSecondary))
                Spacer(Modifier.height(4.dp))
                AmountText(Money.format(stats.forecastAnnualMinor), size = 19, weight = FontWeight.SemiBold)
            }
        }
        if (stats.pendingCount > 0 || stats.trialCount > 0) {
            Spacer(Modifier.height(10.dp))
            val notes = buildList {
                if (stats.pendingCount > 0) {
                    add("其中含待确认 ${stats.pendingCount} 项，年化 ${Money.format(stats.pendingMinor)}")
                }
                if (stats.trialCount > 0) {
                    add("含试用中 ${stats.trialCount} 项，转正后月均 +${Money.format(stats.trialMonthlyDeltaMinor)}")
                }
            }
            Text(
                notes.joinToString("；"),
                style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, color = Warning),
            )
        }
    }
}

@Composable
internal fun EmptyState(onAdd: () -> Unit) {
    GlassCard(padding = PaddingValues(20.dp)) {
        Text("还没有订阅", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink))
        Spacer(Modifier.height(6.dp))
        Text("记一条，30 秒后你会看到每年花掉多少。", style = TextStyle(fontSize = 14.sp, color = InkSecondary))
        Spacer(Modifier.height(14.dp))
        Text(
            "新建订阅",
            modifier = Modifier.clickable(onClick = onAdd),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Accent),
        )
    }
}

@Composable
internal fun PendingSection(
    pending: List<Subscription>,
    states: Map<Subscription, SubscriptionDisplayState>,
    onOpen: (Subscription) -> Unit,
    onConfirm: (Subscription) -> Unit,
    onCancel: (Subscription) -> Unit,
    onSnooze: (Subscription) -> Unit,
) {
    GlassCard(corner = 18.dp, padding = PaddingValues(0.dp), alpha = 0.78f) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryBadge(R.drawable.ic_clock, Warning, 30.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                com.subtrack.ui.state.DashboardState.pendingLabel(pending.size),
                style = TextStyle(fontSize = 17.sp, color = Ink),
            )
            Spacer(Modifier.weight(1f))
            Text("点条目处理", style = TextStyle(fontSize = 12.sp, color = InkSecondary))
        }
        pending.take(3).forEachIndexed { index, sub ->
            InsetDivider(56)
            val overdue = (states[sub] as? SubscriptionDisplayState.PendingConfirm)?.overdueDays ?: 0
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).clickable { onOpen(sub) }) {
                    Text(sub.name, style = TextStyle(fontSize = 15.sp, color = Ink), maxLines = 1)
                    Text(
                        "已过期 $overdue 天 · ${Money.format(sub.priceMinor)}",
                        style = TextStyle(fontSize = 12.sp, color = Warning),
                    )
                }
                PendingAction("已续费") { onConfirm(sub) }
                PendingAction("取消") { onCancel(sub) }
                PendingAction("再说") { onSnooze(sub) }
            }
        }
    }
}

@Composable
private fun PendingAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = TextStyle(fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Medium),
    )
}
