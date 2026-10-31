package com.subtrack.ui.clipboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.subtrack.R
import com.subtrack.core.money.Money
import com.subtrack.domain.parse.PaymentTextParser
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.Warning

/**
 * 剪贴板识别提示条。
 *
 * 产品判断：这是"少点两下"的辅助，不是主角。所以
 * - 只在真的解析出金额时才出现（没识别成功就什么都不显示）；
 * - 用户点"填入"才用，绝不自动改数据；
 * - 可一键忽略，忽略后本轮不再提示。
 */
@Composable
fun ClipboardHintCard(
    parsed: PaymentTextParser.Parsed,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassCard(corner = 18.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), alpha = 0.78f) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(30.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Accent.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "贴",
                    style = TextStyle(fontSize = 14.sp, color = Accent, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "识别到一笔支付",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink),
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    buildString {
                        append(parsed.merchant)
                        append(" · ")
                        append(Money.format(parsed.amountMinor))
                        parsed.source?.let { append(" · ").append(it) }
                    },
                    style = TextStyle(fontSize = 12.sp, color = InkSecondary),
                )
                if (!parsed.isConfident) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "商户没认出来，请核对后再保存",
                        style = TextStyle(fontSize = 11.sp, color = Warning),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "填入",
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent)
                    .clickable(onClick = onUse)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = TextStyle(fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "不用",
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(SegmentBg)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = TextStyle(fontSize = 13.sp, color = InkSecondary),
            )
        }
    }
}
