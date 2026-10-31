package com.subtrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SubTrackTheme

/** 首页的小状态件：快捷筛选 chip、错误卡、加载卡、无结果卡。 */
@Composable
internal fun QuickFilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) Accent
                else if (SubTrackTheme.colors.isDark) SubTrackTheme.colors.fieldBg
                else Color.White.copy(alpha = 0.62f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 13.sp,
                color = if (selected) Color.White else InkSecondary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

@Composable
internal fun ErrorCard(reason: String, onRetry: () -> Unit) {
    GlassCard(corner = 18.dp, padding = PaddingValues(16.dp)) {
        Text("读取数据出错", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink))
        Spacer(Modifier.height(3.dp))
        Text(reason, style = TextStyle(fontSize = 13.sp, color = InkSecondary))
        Spacer(Modifier.height(10.dp))
        Text(
            "重试",
            modifier = Modifier.clickable(onClick = onRetry),
            style = TextStyle(fontSize = 15.sp, color = Accent, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
internal fun LoadingCard() {
    GlassCard(corner = 18.dp, padding = PaddingValues(16.dp)) {
        Text("正在读取…", style = TextStyle(fontSize = 15.sp, color = InkSecondary))
    }
}

/** 筛选无结果的空态。必须说明"是筛选问题"而不是"没有数据"。 */
@Composable
internal fun NoMatchCard(hasAdvanced: Boolean, onReset: () -> Unit) {
    GlassCard(corner = 18.dp, padding = PaddingValues(18.dp)) {
        Text(
            if (hasAdvanced) "没有符合条件的订阅" else "没有订阅",
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            if (hasAdvanced) "换个条件试试，或者重置筛选" else "点右上角加号记一条",
            style = TextStyle(fontSize = 13.sp, color = InkSecondary),
        )
        if (hasAdvanced) {
            Spacer(Modifier.height(10.dp))
            Text(
                "重置筛选",
                modifier = Modifier.clickable(onClick = onReset),
                style = TextStyle(fontSize = 14.sp, color = Accent, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
