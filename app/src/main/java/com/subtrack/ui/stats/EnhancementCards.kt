package com.subtrack.ui.stats

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.core.money.Money
import com.subtrack.domain.stats.BudgetWatch
import com.subtrack.domain.stats.PriceWatch
import com.subtrack.domain.stats.SubscriptionHealth
import com.subtrack.ui.components.AmountText
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import com.subtrack.ui.components.SectionHeader
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SubTrackTheme
import com.subtrack.ui.theme.Success
import com.subtrack.ui.theme.Warning

/**
 * L2 增强卡片集合：预算、涨价、体检。
 *
 * 三张卡都遵循同一个原则：**没有数据就不渲染**，而不是渲染一张空卡说"暂无"。
 * 新用户不该被一堆空模块教育"你还缺什么"。
 */

/** 预算进度卡。只在用户设了预算时出现。 */
@Composable
fun BudgetCard(status: BudgetWatch.Status, onClick: () -> Unit) {
    val colors = SubTrackTheme.colors
    val fillColor = when {
        status.isOver -> colors.danger
        status.isNearLimit -> Warning
        else -> Success
    }
    GlassCard(corner = 18.dp, padding = PaddingValues(16.dp)) {
        // 不写 import kotlin.math.min：直接取 coerce
        val ratio = status.usedRatio.coerceIn(0.0, 1.0).toFloat()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "本月预算",
                style = TextStyle(fontSize = 13.sp, color = InkSecondary, fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${(status.usedRatio * 100).toInt()}%",
                style = TextStyle(fontSize = 13.sp, color = fillColor, fontWeight = FontWeight.SemiBold),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            AmountText(Money.format(status.spentMinor), size = 22, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(
                "/ ${Money.format(status.budgetMinor)}",
                style = TextStyle(fontSize = 13.sp, color = InkSecondary),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        // 进度条：轨道 + 填充
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SegmentBg),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(fillColor),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                status.isOver -> "已超支 ${Money.format(-status.remainingMinor)}"
                status.isNearLimit -> "还剩 ${Money.format(status.remainingMinor)}，注意别超"
                else -> "还剩 ${Money.format(status.remainingMinor)}"
            },
            style = TextStyle(fontSize = 12.sp, color = if (status.isOver) colors.danger else InkSecondary),
        )
    }
}

/** 涨价提示卡。只在检测到变化时出现。 */
@Composable
fun PriceChangeCard(
    changes: List<PriceWatch.Change>,
    onOpen: (String) -> Unit,
) {
    if (changes.isEmpty()) return
    GlassCard(corner = 18.dp, padding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "价格变化",
                style = TextStyle(fontSize = 13.sp, color = InkSecondary, fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${changes.size} 条",
                style = TextStyle(fontSize = 12.sp, color = InkSecondary),
            )
        }
        changes.take(3).forEachIndexed { index, change ->
            if (index > 0) InsetDivider(16)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(change.subscription.id) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        change.subscription.name,
                        style = TextStyle(fontSize = 15.sp, color = Ink),
                    )
                    Text(
                        "${Money.format(change.previousMinor)} → ${Money.format(change.currentMinor)}",
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = InkSecondary,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
                Text(
                    (if (change.isIncrease) "+" else "") + "${change.percent}%",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (change.isIncrease) Warning else Success,
                    ),
                )
            }
        }
    }
}

/** 体检结果卡。只在有 finding 时出现。 */
@Composable
fun HealthCard(
    findings: List<SubscriptionHealth.Finding>,
    onOpen: (String) -> Unit,
) {
    if (findings.isEmpty()) return
    GlassCard(corner = 18.dp, padding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "订阅体检",
                style = TextStyle(fontSize = 13.sp, color = InkSecondary, fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.weight(1f))
            val warns = findings.count { it.severity == SubscriptionHealth.Severity.WARN }
            if (warns > 0) {
                Text(
                    "$warns 项需要注意",
                    style = TextStyle(fontSize = 12.sp, color = Warning, fontWeight = FontWeight.Medium),
                )
            }
        }
        findings.take(4).forEachIndexed { index, f ->
            if (index > 0) InsetDivider(16)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(f.id) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态点：警告橙、建议灰
                Box(
                    Modifier
                        .width(7.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (f.severity == SubscriptionHealth.Severity.WARN) Warning else InkSecondary.copy(alpha = 0.4f),
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, style = TextStyle(fontSize = 14.sp, color = Ink))
                    Spacer(Modifier.height(1.dp))
                    Text(
                        f.detail,
                        style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, color = InkSecondary),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    f.suggestedAction,
                    style = TextStyle(fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

/** 未设汇率时的提示条（多币种开启才出现）。 */
@Composable
fun MissingRateHint(codes: List<String>, onClick: () -> Unit) {
    if (codes.isEmpty()) return
    GlassCard(corner = 18.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "还差 ${codes.joinToString("、")} 的汇率",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Warning),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "设好汇率才能把这些订阅折算进统计",
                    style = TextStyle(fontSize = 12.sp, color = InkSecondary),
                )
            }
            Text("去设置", style = TextStyle(fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Medium))
        }
    }
}
