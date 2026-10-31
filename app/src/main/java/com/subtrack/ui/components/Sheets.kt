package com.subtrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.domain.stats.StatsEngine
import com.subtrack.ui.theme.Type
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Danger
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.InkTertiary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SheetBg

/**
 * 统计数字的展开面板（AC-35）。
 *
 * 产品意图：这个 App 的核心卖点是"数字可核对"，所以每个大数字都必须能点开看到
 * 公式与参与计算的条目。否则用户没法验证，跟竞品那个孤立的月均数字没区别。
 */
@Composable
fun BreakdownSheet(
    breakdown: StatsEngine.Breakdown,
    onDismiss: () -> Unit,
) {
    DismissibleOverlay(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                // 主题感知的弹层底色：深色模式下用深色底，配 Ink 系文字才可读
                // （固定 Color.White 会在深色下变成白底配 #F5F5F7 的近白字）
                .background(SheetBg)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(InkTertiary))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(breakdown.title, style = Type.title3, color = Ink)
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    Modifier.clickable(onClick = onDismiss),
                    style = TextStyle(fontSize = 16.sp, color = Accent, fontWeight = FontWeight.Medium),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                breakdown.formula,
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = InkSecondary),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                com.subtrack.core.money.Money.format(breakdown.totalMinor),
                style = TextStyle(
                    fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                    letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum",
                ),
            )
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                breakdown.lines.forEach { line ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = TextStyle(fontSize = 15.sp, color = Ink))
                            Spacer(Modifier.height(1.dp))
                            Text(line.detail, style = TextStyle(fontSize = 12.sp, color = InkSecondary))
                        }
                        Text(
                            com.subtrack.core.money.Money.format(line.annualMinor),
                            style = TextStyle(fontSize = 15.sp, color = Ink, fontFeatureSettings = "tnum"),
                        )
                    }
                }
                if (breakdown.lines.isEmpty()) {
                    Text("暂无参与计算的条目。", style = TextStyle(fontSize = 14.sp, color = InkSecondary))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * 通用底部弹层容器：半透明遮罩 + 底部内容，点击遮罩关闭。
 * 用它而不是 ModalBottomSheet，是因为这里的内容高度不稳定，Modal 的动画在低端机上会闪。
 */
@Composable
fun DismissibleOverlay(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss),
        )
        Box(Modifier.align(Alignment.BottomCenter)) { content() }
    }
}

/** 确认对话框（AC-08 删除、AC-15 恢复日期等）。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    confirmTag: String? = null,
    dismissLabel: String? = "取消",
) {
    // 系统返回＝取消，不要带着未确认的危险操作退出页面。
    androidx.activity.compose.BackHandler(enabled = true, onBack = onDismiss)
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
                    .padding(horizontal = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SheetBg)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = InkSecondary),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    // 纯告知型对话框（如导入结果）不显示"取消"：两个按钮做同一件事会让人以为可以撤回。
                    if (dismissLabel != null) {
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SegmentBg)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(dismissLabel, style = TextStyle(fontSize = 16.sp, color = Ink))
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .then(if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (destructive) com.subtrack.ui.theme.Danger else Accent)
                            .clickable(onClick = onConfirm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            confirmLabel,
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                        )
                    }
                }
            }
        }
    }
}
