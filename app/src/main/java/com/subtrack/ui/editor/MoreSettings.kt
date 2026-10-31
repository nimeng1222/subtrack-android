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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.InkTertiary
import com.subtrack.ui.theme.SegmentBg

/**
 * 编辑页的"更多设置"折叠区：自动续费、试用、单条提醒提前量、备注。
 * 单独一个文件是因为这块字段多但彼此独立，留在 EditorSheet 里会把主流程冲淡。
 */
@Composable
internal fun MoreSettings(
    autoRenew: Boolean,
    onAutoRenew: (Boolean) -> Unit,
    isTrial: Boolean,
    onTrial: (Boolean) -> Unit,
    trialEnd: LocalDate,
    onTrialEnd: (LocalDate) -> Unit,
    offsets: List<Int>,
    onOffsets: (List<Int>) -> Unit,
    note: String,
    onNote: (String) -> Unit,
    today: LocalDate,
) {
    var pickingTrialEnd by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        FieldSurface(padding = 0.dp) {
            ToggleRow("自动续费", "关掉后只提醒、不推进月均", autoRenew, onAutoRenew)
            InsetLine()
            ToggleRow("试用中", "试用结束前会单独提醒转正", isTrial, onTrial)
            if (isTrial) {
                InsetLine()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "试用结束日",
                        style = TextStyle(fontSize = 15.sp, color = Ink),
                        modifier = Modifier.weight(1f),
                    )
                    Stepper(
                        "${trialEnd.monthValue}月${trialEnd.dayOfMonth}日",
                        onDelta = { delta -> onTrialEnd(trialEnd.plusDays(delta)) },
                        onOpen = { pickingTrialEnd = true },
                        tag = "editor-trial-date",
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        FieldSurface {
            Text("提醒提前量（覆盖全局默认）", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
            Spacer(Modifier.height(8.dp))
            Row {
                listOf(0, 1, 3, 7, 14).forEach { d ->
                    val selected = offsets.contains(d)
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Accent else SegmentBg)
                            .clickable {
                                val next = if (selected) offsets - d else offsets + d
                                val onDay = next.contains(0)
                                val ahead = next.filter { it > 0 }.maxOrNull()
                                onOffsets(
                                    listOfNotNull(ahead, if (onDay) 0 else null).ifEmpty { listOf(0) },
                                )
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (d == 0) "当天" else "$d 天",
                            style = TextStyle(fontSize = 12.sp, color = if (selected) Color.White else Ink),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        FieldSurface {
            Text("备注（可选）", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = note,
                onValueChange = { if (it.length <= 200) onNote(it) },
                textStyle = TextStyle(fontSize = 15.sp, color = Ink, lineHeight = 21.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                decorationBox = { inner ->
                    if (note.isEmpty()) {
                        Text(
                            "比如：家庭共享、年付优惠",
                            style = TextStyle(fontSize = 15.sp, color = InkTertiary),
                        )
                    }
                    inner()
                },
            )
            Text(
                "${note.length}/200",
                style = TextStyle(fontSize = 11.sp, color = InkSecondary),
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
    if (pickingTrialEnd) {
        EditorDatePickerDialog(
            title = "选择试用结束日",
            initial = trialEnd,
            today = today,
            allowFuture = true,
            onPick = { onTrialEnd(it); pickingTrialEnd = false },
            onDismiss = { pickingTrialEnd = false },
        )
    }

}
