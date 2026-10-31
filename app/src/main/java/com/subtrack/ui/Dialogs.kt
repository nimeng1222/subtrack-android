package com.subtrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.state.SubscriptionActions
import com.subtrack.ui.components.nativeShadow
import com.subtrack.ui.state.UiMessage
import java.time.LocalDate
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SheetBg
import com.subtrack.ui.theme.SubTrackTheme

/**
 * 恢复订阅的确认层。
 *
 * AC-15 明确要求：暂停期间可能已经跨过多个周期，系统**不得擅自猜测**新的下次到期日。
 * 所以这里给一个"建议值 + 前后微调"的选择，最终由用户拍板。
 */
@Composable
fun ResumeDialog(
    sub: Subscription,
    today: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember(sub.id) {
        mutableStateOf(SubscriptionActions.suggestedResumeDate(sub, today))
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss),
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .padding(horizontal = 36.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SheetBg)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "恢复「${sub.name}」",
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "暂停期间可能已跨过多个周期，请确认新的下次扣费日。",
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = InkSecondary),
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    listOf(-1L, 0L, 1L).forEach { delta ->
                        val d = chosen.plusDays(delta)
                        val selected = d == chosen
                        Text(
                            text = "${d.monthValue}月${d.dayOfMonth}日",
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Accent else SegmentBg)
                                .clickable { chosen = d }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = if (selected) Color.White else Ink,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.width(220.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(end = 5.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SegmentBg)
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("取消", style = TextStyle(fontSize = 16.sp, color = Ink)) }
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(start = 5.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Accent)
                            .clickable { onConfirm(chosen) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "恢复",
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                        )
                    }
                }
            }
        }
    }
}

/** 一次性提示：轻量 toast 与导入结果摘要。 */
@Composable
fun MessageOverlay(message: UiMessage, onDismiss: () -> Unit) {
    when (message) {
        is UiMessage.ImportSummary -> {
            val touched = message.added + message.skipped + message.addedPayments > 0
            com.subtrack.ui.components.ConfirmDialog(
                title = when {
                    // 部分成功（数据已入库、设置/调度失败）不能报成"导入失败"，
                    // 也不能把警告吞掉——用户必须知道发生了什么
                    touched && message.error != null -> "导入完成（有警告）"
                    touched -> "导入完成"
                    else -> "导入失败"
                },
                message = if (!touched) {
                    "${message.error ?: "导入失败"}\n\n现有数据没有任何改动。"
                } else {
                    buildString {
                        append("新增 ${message.added} 条 / 跳过 ${message.skipped} 条（已存在）")
                        if (message.corrected > 0) append(" / 修正 ${message.corrected} 条")
                        if (message.addedPayments > 0) append("\n补入 ${message.addedPayments} 笔流水")
                        message.error?.let { append("\n\n注意：$it") }
                        // CSV 是逐行容错的，必须把坏行明确报出来，不能悄悄丢数据
                        if (message.csvErrors.isNotEmpty()) {
                            append("\n\n有 ${message.csvErrors.size} 行未能导入：")
                            message.csvErrors.take(5).forEach { append("\n· $it") }
                            if (message.csvErrors.size > 5) {
                                append("\n… 另有 ${message.csvErrors.size - 5} 行")
                            }
                        }
                    }
                },
                confirmLabel = "知道了",
                onConfirm = onDismiss,
                onDismiss = onDismiss,
                dismissLabel = null,
            )
        }

        is UiMessage.Toast -> {
            Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    message.text,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .nativeShadow(14.dp, blur = 18.dp, dy = 8.dp, color = 0x240F172A)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E).copy(alpha = 0.94f))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    style = TextStyle(fontSize = 14.sp, color = Color.White),
                )
            }
        }

        is UiMessage.LoadError -> Unit
    }
}

/** 删除后的撤销条（AC-08：5 秒内可撤销）。 */
@Composable
fun UndoBar(name: String, onUndo: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .padding(horizontal = 20.dp)
                .nativeShadow(18.dp, blur = 22.dp, dy = 10.dp, color = 0x240F172A)
                .clip(RoundedCornerShape(16.dp))
                .background(if (SubTrackTheme.colors.isDark) SubTrackTheme.colors.sheetBg else Color.White.copy(alpha = 0.97f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已删除「$name」", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink))
            Spacer(Modifier.width(16.dp))
            Text(
                "撤销",
                modifier = Modifier.clickable(onClick = onUndo),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent),
            )
        }
    }
}
