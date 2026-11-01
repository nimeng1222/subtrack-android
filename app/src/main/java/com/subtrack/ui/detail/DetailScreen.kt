package com.subtrack.ui.detail

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.subtrack.core.money.Money
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.state.DisplayStateResolver
import com.subtrack.domain.state.SubscriptionDisplayState
import com.subtrack.domain.stats.Annualizer
import com.subtrack.ui.Categories
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.ChevronIcon
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import com.subtrack.ui.components.SectionHeader
import com.subtrack.ui.components.nativeShadow
import com.subtrack.ui.home.cycleLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Danger
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary

@Composable
fun DetailScreen(
    sub: Subscription,
    today: LocalDate,
    paidTotalMinor: Long,
    paidCount: Int = 0,
    onBack: () -> Unit,
    onRenew: () -> Unit,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onConvertTrial: () -> Unit,
    onExpire: () -> Unit,
) {
    val category = Categories.of(sub.categoryId)
    val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val monthly = Annualizer.monthlyAverageMinor(
        Annualizer.annualizeMinor(sub.priceMinor, sub.cycle, sub.customCycleDays),
    )
    // 自动续订（1.9.7）后，“标记已续费”只用于已到期、等待确认的那一期；
    // 未到期时不再展示按钮，避免把流水记到未来日期并多推一期。
    val displayState = DisplayStateResolver.resolve(sub, today, hasPaymentOnDueDate = false)
    val pendingConfirm = displayState is SubscriptionDisplayState.PendingConfirm

    AmbientBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Text("订阅详情", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                Spacer(Modifier.weight(1f))
                Text(
                    "编辑",
                    modifier = Modifier.clickable(onClick = onEdit),
                    style = TextStyle(fontSize = 17.sp, color = Accent, fontWeight = FontWeight.Medium),
                )
            }

            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CategoryBadge(category.iconRes, category.color, 68.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    sub.name,
                    style = TextStyle(
                        fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                        color = Ink, letterSpacing = (-0.5).sp,
                    ),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${cycleLabel(sub.cycle)} · ${if (sub.autoRenew) "自动续费" else "手动续费"} · ${category.name}",
                    style = TextStyle(fontSize = 14.sp, color = InkSecondary),
                )
                Spacer(Modifier.height(16.dp))
                AmountText(Money.format(sub.priceMinor), size = 34, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("每${cycleLabel(sub.cycle).removeSuffix("付")}", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
            }

            Spacer(Modifier.height(26.dp))
            SectionHeader("订阅信息")
            Spacer(Modifier.height(10.dp))
            GlassCard(padding = PaddingValues(0.dp)) {
                InfoRow(
                    R.drawable.ic_calendar,
                    "下次扣费",
                    sub.nextChargeDate?.format(DateTimeFormatter.ofPattern("M月d日")) ?: "未设置",
                    valueColor = Ink,
                )
                InsetDivider(48)
                InfoRow(R.drawable.ic_clock, "开始日期", sub.anchorDate.format(dateFmt))
                InsetDivider(48)
                InfoRow(
                    R.drawable.ic_card,
                    "已付总额",
                    // 没有流水时写清楚"还没有"，不要用 ¥0.00 让人以为金额被吞了。
                    if (paidCount <= 0) "还没有付款记录" else "${Money.format(paidTotalMinor)}（$paidCount 笔）",
                    valueColor = Ink,
                    showChevron = false,
                )
                InsetDivider(48)
                InfoRow(R.drawable.ic_chart, "折算月均", Money.format(monthly), valueColor = Ink)
            }

            Spacer(Modifier.height(22.dp))
            SectionHeader("提醒")
            Spacer(Modifier.height(10.dp))
            GlassCard(padding = PaddingValues(0.dp)) {
                InfoRow(
                    R.drawable.ic_bell,
                    "提醒",
                    reminderSummary(sub.reminderOffsets),
                    valueColor = Ink,
                    showChevron = false,
                )
                InsetDivider(48)
                InfoRow(R.drawable.ic_clock, "提醒时间", "09:00")
            }
            Spacer(Modifier.height(10.dp))
            if (pendingConfirm) {
                Text(
                    "本期已到期：确认后会按周期推进下次扣费，并补记这一期。",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = InkSecondary),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .nativeShadow(corner = 16.dp, blur = 18.dp, dy = 8.dp, color = 0x3D0A7CFF)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Accent)
                        .clickable(onClick = onRenew),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "标记已续费",
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            GlassCard(padding = PaddingValues(0.dp)) {
                ActionRow(R.drawable.ic_receipt, "编辑订阅", onEdit)
                InsetDivider(48)
                when (sub.status) {
                    SubscriptionStatus.PAUSED -> {
                        ActionRow(R.drawable.ic_clock, "恢复订阅", onResume, hint = "继续计算月均")
                        InsetDivider(48)
                    }
                    SubscriptionStatus.CANCELED, SubscriptionStatus.EXPIRED -> Unit
                    SubscriptionStatus.TRIAL -> {
                        // AC-50：试用结束未处理时给"已转正"与"已取消"两个动作
                        ActionRow(R.drawable.ic_check, "试用转正", onConvertTrial, hint = "按周期滚动并记一笔")
                        InsetDivider(48)
                    }
                    else -> {
                        ActionRow(R.drawable.ic_clock, "暂停提醒", onPause)
                        InsetDivider(48)
                    }
                }
                ActionRow(R.drawable.ic_trash, "取消订阅", onCancel, hint = "保留历史，停止提醒")
                if (sub.status != SubscriptionStatus.EXPIRED && sub.status != SubscriptionStatus.CANCELED) {
                    InsetDivider(48)
                    ActionRow(R.drawable.ic_info, "标记已过期", onExpire, hint = "走完自然生命周期")
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text("删除这条记录", style = TextStyle(fontSize = 17.sp, color = Danger))
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun reminderSummary(offsets: List<Int>): String {
    if (offsets.isEmpty()) return "已关闭"
    val hasOnDay = offsets.contains(0)
    val ahead = offsets.filter { it > 0 }.maxOrNull()
    return when {
        ahead == null && hasOnDay -> "到期当天"
        hasOnDay -> "提前 $ahead 天 + 当天"
        ahead != null -> "提前 $ahead 天"
        else -> "已关闭"
    }
}

@Composable
private fun InfoRow(
    icon: Int,
    label: String,
    value: String?,
    valueColor: Color = InkSecondary,
    showChevron: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = InkSecondary)
        Spacer(Modifier.width(13.dp))
        Text(label, style = TextStyle(fontSize = 17.sp, color = Ink), modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = TextStyle(fontSize = 17.sp, color = valueColor, fontFeatureSettings = "tnum"))
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            ChevronIcon()
        }
    }
}

@Composable
private fun ActionRow(icon: Int, title: String, onClick: () -> Unit, hint: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = InkSecondary)
        Spacer(Modifier.width(13.dp))
        Text(title, style = TextStyle(fontSize = 17.sp, color = Ink), modifier = Modifier.weight(1f))
        if (hint != null) {
            Text(hint, style = TextStyle(fontSize = 12.sp, color = InkSecondary))
        }
    }
}
