package com.subtrack.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Danger
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.InkTertiary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SheetBg
import com.subtrack.ui.theme.Type
import java.time.LocalDate
import java.time.YearMonth

/** UTC 毫秒 ↔ LocalDate：DatePicker 口径，统一 UTC 才不会出现"选 5 号显示 4 号"。 */
internal fun editorDateToUtcMillis(date: LocalDate): Long =
    date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun utcMillisToEditorDate(millis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()

/** 开始日期不能晚于今天；下次扣费 / 试用结束日可以选未来。 */
internal fun isEditorDateSelectable(utcMillis: Long, today: LocalDate, allowFuture: Boolean): Boolean {
    if (allowFuture) return true
    return utcMillis <= editorDateToUtcMillis(today)
}

private val WeekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 应用自己的日期选择器。
 *
 * 之前这里直接用 Material 的 DatePickerDialog：英文月份、英文星期、Material 的字号与配色，
 * 和首页/统计页完全不是一套语言。现在换成自己画的月历：
 * 中文年月与星期、应用强调色、同样的圆角与字号，选中大圆点、今天细描边、不可选的日期变淡。
 */
@Composable
internal fun EditorDatePickerDialog(
    title: String,
    initial: LocalDate,
    today: LocalDate,
    allowFuture: Boolean,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    var month by remember(initial) { mutableStateOf(YearMonth.from(initial)) }
    val minMonth = YearMonth.from(today).minusYears(10)
    val maxMonth = if (allowFuture) YearMonth.from(today).plusYears(10) else YearMonth.from(today)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // 用不透明的弹层底色：日历后面还有一个编辑表单，半透明玻璃卡会让两页文字叠在一起。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SheetBg)
                .testTag("calendar-dialog"),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(title, style = Type.title3, color = Ink)
                Spacer(Modifier.height(14.dp))

                // 年月 + 翻页
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${month.year} 年 ${month.monthValue} 月",
                        style = Type.headline,
                        color = Ink,
                        modifier = Modifier.weight(1f).testTag("calendar-month"),
                    )
                    MonthArrow("‹", enabled = month > minMonth, tag = "calendar-prev") {
                        month = month.minusMonths(1)
                    }
                    Spacer(Modifier.width(6.dp))
                    MonthArrow("›", enabled = month < maxMonth, tag = "calendar-next") {
                        month = month.plusMonths(1)
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth()) {
                    WeekdayLabels.forEach { label ->
                        Text(
                            label,
                            style = Type.caption,
                            color = InkSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                CalendarGrid(
                    month = month,
                    selected = selected,
                    today = today,
                    allowFuture = allowFuture,
                    onSelect = { selected = it },
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    "已选：${selected.year} 年 ${selected.monthValue} 月 ${selected.dayOfMonth} 日",
                    style = Type.footnote,
                    color = InkSecondary,
                    modifier = Modifier.testTag("calendar-selected"),
                )
                if (!allowFuture) {
                    Spacer(Modifier.height(4.dp))
                    Text("开始日期不能晚于今天；更晚的日期不可点。", style = Type.caption, color = InkTertiary)
                }
                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogButton(
                        text = "取消",
                        onClick = onDismiss,
                        tag = "date-picker-cancel",
                        primary = false,
                        modifier = Modifier.weight(1f),
                    )
                    DialogButton(
                        text = "确定",
                        onClick = { onPick(selected) },
                        tag = "date-picker-confirm",
                        primary = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(glyph: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(SegmentBg.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = TextStyleLite,
            color = if (enabled) Ink else InkTertiary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val TextStyleLite = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 20.sp)

@Composable
private fun CalendarGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    allowFuture: Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    val firstOfMonth = month.atDay(1)
    val leading = (firstOfMonth.dayOfWeek.value - 1) // 周一为一周第一天
    val days = month.lengthOfMonth()
    val cells = buildList<LocalDate?> {
        repeat(leading) { add(null) }
        for (d in 1..days) add(month.atDay(d))
        while (size % 7 != 0) add(null)
    }
    Column(Modifier.fillMaxWidth()) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                        if (date == null) return@Box
                        val selectable = isEditorDateSelectable(editorDateToUtcMillis(date), today, allowFuture)
                        val isSelected = date == selected
                        val isToday = date == today
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Accent else Color.Transparent)
                                .then(
                                    if (!isSelected && isToday) {
                                        Modifier.border(1.dp, Accent.copy(alpha = 0.55f), CircleShape)
                                    } else Modifier,
                                )
                                .clickable(enabled = selectable) { onSelect(date) }
                                .testTag("calendar-day-${date}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = Type.subhead.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                                color = when {
                                    isSelected -> Color.White
                                    !selectable -> InkTertiary.copy(alpha = 0.45f)
                                    isToday -> Accent
                                    else -> Ink
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    tag: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (primary) Accent else SegmentBg)
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Type.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) Color.White else Ink,
        )
    }
}
