package com.subtrack.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.Separator

/** AC-05：就地校验，返回第一条错误原因，不清空已填内容。
 *  顶部"保存"与底部"保存订阅"共用这一份；自定义周期的范围与 CycleEngine/导入同源。 */
internal fun validate(
    name: String,
    minor: Long?,
    anchor: LocalDate,
    today: LocalDate,
    cycle: BillingCycleType = BillingCycleType.MONTHLY,
    customDays: Int? = null,
): String? = when {
    name.isBlank() -> "填个名字才能保存"
    name.length > 40 -> "名称最多 40 个字符"
    minor == null || minor < 0L -> "填入有效金额（0~100 亿元）"
    minor > com.subtrack.domain.model.Limits.MAX_PRICE_MINOR -> "金额超出可安全计算的上限"
    anchor.isAfter(today) -> "开始日期不能晚于今天"
    cycle == BillingCycleType.CUSTOM_DAYS &&
        !com.subtrack.domain.model.Limits.isValidCustomCycleDays(customDays) ->
        "自定义天数需在 ${com.subtrack.domain.model.Limits.CUSTOM_CYCLE_DAYS_MIN}" +
            "~${com.subtrack.domain.model.Limits.CUSTOM_CYCLE_DAYS_MAX} 之间"
    else -> null
}

/**
 * 一行日期：左边标签，右边固定宽度的 [Stepper]。
 *
 * 之前「下次扣费」这一行右侧还塞了"按周期重算"，把 ± 与日期整体往左挤，
 * 两行的日期框上下错开、右边界也不齐。现在两行只放同一个固定宽度的微调器，
 * 附加动作统一放到日期区下方。
 */
@Composable
internal fun DateRow(
    label: String,
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
    tag: String? = null,
    onPickDate: (() -> Unit)? = null,
) {
    FieldSurface(padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TextStyle(fontSize = 15.sp, color = Ink), modifier = Modifier.weight(1f))
            Stepper(
                "${date.year}-${"%02d".format(date.monthValue)}-${"%02d".format(date.dayOfMonth)}",
                onDelta = { onChange(date.plusDays(it)) },
                onOpen = onPickDate,
                tag = tag,
            )
        }
    }
}

/**
 * 日期微调器：[−] 日期 [+]，日期本身可点开日历。
 * 固定 208dp 宽（± 各 48dp 命中区 + 中间日期 chip），两行日期才会严格上下对齐。
 */
@Composable
internal fun Stepper(
    text: String,
    onDelta: (Long) -> Unit,
    onOpen: (() -> Unit)? = null,
    tag: String? = null,
) {
    Row(
        Modifier.width(208.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SegmentBg)
                .clickable { onDelta(-1) }
                .testTag("${tag ?: "stepper"}-minus"),
            contentAlignment = Alignment.Center,
        ) {
            Text("−", style = TextStyle(fontSize = 18.sp, color = Ink))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(if (onOpen != null) Modifier.background(SegmentBg).clickable(onClick = onOpen) else Modifier)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = TextStyle(fontSize = 14.sp, color = Ink, fontFeatureSettings = "tnum"),
                modifier = Modifier.semantics {
                    if (onOpen != null) contentDescription = "选择日期 $text"
                },
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SegmentBg)
                .clickable { onDelta(1) }
                .testTag("${tag ?: "stepper"}-plus"),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", style = TextStyle(fontSize = 18.sp, color = Ink))
        }
    }
}

@Composable
internal fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 15.sp, color = Ink))
            Spacer(Modifier.height(1.dp))
            Text(hint, style = TextStyle(fontSize = 12.sp, color = InkSecondary))
        }
        IosSwitch(checked, onChange)
    }
}

@Composable
internal fun InsetLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(com.subtrack.ui.theme.Separator),
    )
}
