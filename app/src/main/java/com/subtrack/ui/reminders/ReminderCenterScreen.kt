package com.subtrack.ui.reminders

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.R
import com.subtrack.core.money.Money
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.state.DisplayStateResolver
import com.subtrack.domain.state.SubscriptionDisplayState
import com.subtrack.ui.Categories
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.BottomFade
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.ChevronIcon
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import com.subtrack.ui.components.SectionHeader
import com.subtrack.ui.home.FloatingTabBar
import com.subtrack.ui.home.cycleLabel
import com.subtrack.ui.home.relativeLabel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Warning

@Composable
fun ReminderCenterScreen(
    subscriptions: List<Subscription>,
    today: LocalDate,
    onBack: () -> Unit,
    onOpen: (Subscription) -> Unit,
    onSelectTab: (Int) -> Unit,
    onSnooze: (Subscription) -> Unit,
    onMute: (Subscription) -> Unit,
) {
    val states = subscriptions.associateWith { DisplayStateResolver.resolve(it, today, hasPaymentOnDueDate = false) }
    val pending = subscriptions.filter { states[it] is SubscriptionDisplayState.PendingConfirm }
    val upcoming = subscriptions
        .filter { states[it] == SubscriptionDisplayState.Active || states[it] == SubscriptionDisplayState.Trial }
        .filter { sub ->
            val next = sub.nextChargeDate ?: return@filter false
            !next.isBefore(today) && ChronoUnit.DAYS.between(today, next) <= 7
        }
        .sortedBy { it.nextChargeDate }

    AmbientBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 150.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.clickable(onClick = onBack), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_back),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Accent,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text("返回", style = TextStyle(fontSize = 17.sp, color = Accent))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("提醒中心", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(52.dp))
                    }
                }
                item {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "待确认 · ${pending.size}",
                        style = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Ink, letterSpacing = (-0.5).sp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (pending.isEmpty()) {
                    item {
                        Text("没有待确认的扣费。", style = TextStyle(fontSize = 14.sp, color = InkSecondary))
                    }
                } else {
                    item {
                        GlassCard(padding = PaddingValues(0.dp)) {
                            pending.forEachIndexed { index, sub ->
                                ReminderRow(sub, today, true, onOpen, onSnooze, onMute)
                                if (index != pending.lastIndex) InsetDivider(56)
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(26.dp))
                    SectionHeader("未来 7 天 · ${upcoming.size}")
                    Spacer(Modifier.height(10.dp))
                }
                if (upcoming.isEmpty()) {
                    item { Text("未来 7 天没有要扣的订阅。", style = TextStyle(fontSize = 14.sp, color = InkSecondary)) }
                } else {
                    item {
                        GlassCard(padding = PaddingValues(0.dp)) {
                            upcoming.forEachIndexed { index, sub ->
                                ReminderRow(sub, today, false, onOpen, onSnooze, onMute)
                                if (index != upcoming.lastIndex) InsetDivider(56)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
            BottomFade(Modifier.align(Alignment.BottomCenter))
            FloatingTabBar(
                selected = 0,
                onSelect = onSelectTab,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun ReminderRow(
    sub: Subscription,
    today: LocalDate,
    pending: Boolean,
    onOpen: (Subscription) -> Unit,
    onSnooze: (Subscription) -> Unit,
    onMute: (Subscription) -> Unit,
) {
    val category = Categories.of(sub.categoryId)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(sub) }
            .padding(start = 12.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(category.iconRes, category.color, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(sub.name, style = TextStyle(fontSize = 17.sp, lineHeight = 21.sp, color = Ink))
            Text(
                "${cycleLabel(sub.cycle)} · ${relativeLabel(sub.nextChargeDate, today)}",
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = if (pending) Warning else InkSecondary),
            )
        }
        AmountText(Money.format(sub.priceMinor), size = 17, color = Ink)
        Spacer(Modifier.width(7.dp))
        ChevronIcon()
    }
}
