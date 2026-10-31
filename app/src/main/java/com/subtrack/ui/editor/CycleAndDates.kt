package com.subtrack.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.ui.Categories
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.SectionHeader
import java.time.LocalDate
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SurfaceSecondary

/** 9 种周期，顺序即展示顺序。 */
internal val EditorCycleOptions = listOf(
    BillingCycleType.MONTHLY to "月付",
    BillingCycleType.QUARTERLY to "季付",
    BillingCycleType.SEMIANNUAL to "半年付",
    BillingCycleType.YEARLY to "年付",
    BillingCycleType.WEEKLY to "周付",
    BillingCycleType.BIWEEKLY to "双周付",
    BillingCycleType.FOUR_WEEKLY to "四周付",
    BillingCycleType.CUSTOM_DAYS to "自定义",
    BillingCycleType.ONE_TIME to "一次性",
)

/**
 * 周期 + 日期区块。AC-03 要求"选周期立即推算下次到期日，且允许手动覆盖"，
 * 所以这里既有周期选择（触发重算），也有逐日微调（手动覆盖）。
 */
@Composable
internal fun CycleAndDates(
    cycle: BillingCycleType,
    onCycle: (BillingCycleType) -> Unit,
    customDays: String,
    onCustomDays: (String) -> Unit,
    anchor: LocalDate,
    onAnchor: (LocalDate) -> Unit,
    nextCharge: LocalDate,
    onNextCharge: (LocalDate) -> Unit,
    onRecalc: () -> Unit,
    today: LocalDate,
    /** 新建时保存会补记一笔首期付款；编辑时不会，所以两处提示分开写。 */
    isNew: Boolean = false,
) {
        // 点了哪一行就弹哪个日历：开始日期不允许选未来，下次扣费可以。
        var picking by remember { mutableStateOf<String?>(null) }
        SectionHeader("扣费周期")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorCycleOptions.forEach { (type, label) ->
                val selected = cycle == type
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Accent else SegmentBg)
                        .clickable { onCycle(type) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        label,
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = if (selected) Color.White else Ink,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
        if (cycle == BillingCycleType.CUSTOM_DAYS) {
            Spacer(Modifier.height(10.dp))
            FieldSurface {
                Text("自定义周期天数（1-3650）", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = customDays,
                    onValueChange = { onCustomDays(it.filter { c -> c.isDigit() }.take(8)) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 20.sp, color = Ink, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth().testTag("editor-custom-days"),
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionHeader("日期")
        Spacer(Modifier.height(8.dp))
        DateRow(
            "开始日期",
            anchor,
            onChange = { onAnchor(it) },
            tag = "editor-anchor-date",
            onPickDate = { picking = "anchor" },
        )
        Spacer(Modifier.height(8.dp))
        DateRow(
            "下次扣费",
            nextCharge,
            onChange = { onNextCharge(it) },
            tag = "editor-next-date",
            onPickDate = { picking = "next" },
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isNew) "保存时会按开始日期记一笔已付；改日期不会重复记。" else "改了周期或开始日期后，可让下次扣费重新推算。",
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = InkSecondary),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "按周期重算",
                modifier = Modifier
                    .testTag("editor-recalc")
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onRecalc() }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                style = TextStyle(fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Medium),
            )
        }
        Spacer(Modifier.height(20.dp))

        when (picking) {
            "anchor" -> EditorDatePickerDialog(
                title = "选择开始日期",
                initial = anchor,
                today = today,
                allowFuture = false,
                onPick = { onAnchor(it); picking = null },
                onDismiss = { picking = null },
            )
            "next" -> EditorDatePickerDialog(
                title = "选择下次扣费日",
                initial = nextCharge,
                today = today,
                allowFuture = true,
                onPick = { onNextCharge(it); picking = null },
                onDismiss = { picking = null },
            )
        }
}

/**
 * 分类选择器。用横向滚动的徽标条而不是下拉菜单：
 * 分类是这一页最高频的视觉判断（"这条是哪个服务"），露出比收起来快。
 */
@Composable
internal fun CategoryPicker(selectedId: String, onPick: (String) -> Unit) {
    SectionHeader("分类")
    Spacer(Modifier.height(10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Categories.all.forEach { category ->
            val active = category.id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) SurfaceSecondary else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) Accent.copy(alpha = 0.35f) else Color.Transparent,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onPick(category.id) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                CategoryBadge(category.iconRes, category.color, 34.dp)
                Spacer(Modifier.height(5.dp))
                Text(
                    category.name,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = if (active) Ink else InkSecondary,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    ),
                )
            }
        }
    }
}
