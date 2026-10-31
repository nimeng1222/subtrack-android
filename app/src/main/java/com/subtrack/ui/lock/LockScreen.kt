package com.subtrack.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.nativeShadow
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SubTrackTheme

/**
 * 锁屏遮罩。不使用独立 Activity：那样会在最近任务里留下一个"锁"的截图，
 * 反而泄露"这里有东西被锁着"。用全屏 Composable 覆盖即可。
 */
@Composable
fun LockScreen(
    onUnlock: () -> Unit,
    onDisable: (() -> Unit)? = null,
    unavailableHint: String? = null,
) {
    AmbientBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 锁形图标：两个圆角矩形拼，不引图标资源
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(26.dp, 20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Accent),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "订阅台账已锁定",
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    unavailableHint ?: "用指纹或面容解锁",
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = InkSecondary),
                )
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .nativeShadow(14.dp, blur = 16.dp, dy = 7.dp, color = 0x3D0A7CFF)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Accent)
                        .clickable(onClick = onUnlock),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "解锁",
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                    )
                }
                if (onDisable != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "关闭应用锁",
                        modifier = Modifier.clickable(onClick = onDisable).padding(8.dp),
                        style = TextStyle(fontSize = 13.sp, color = InkSecondary),
                    )
                }
            }
        }
    }
}

/** 设置页里的应用锁开关行。设备不支持时给出明确原因而不是静默禁用。 */
@Composable
fun AppLockRow(
    enabled: Boolean,
    availability: AppLock.Availability,
    onToggle: (Boolean) -> Unit,
) {
    val ready = availability == AppLock.Availability.READY
    val subtitle = when (availability) {
        AppLock.Availability.READY -> "打开应用时需要指纹或面容验证"
        AppLock.Availability.NOT_ENROLLED -> "系统里还没有录入指纹或面容"
        AppLock.Availability.NO_HARDWARE -> "这台设备没有生物识别硬件"
        AppLock.Availability.HW_UNAVAILABLE -> "生物识别硬件暂时不可用"
        AppLock.Availability.UNAVAILABLE -> "当前系统无法使用生物识别"
    }
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (ready) Modifier.clickable { onToggle(!enabled) } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("应用锁", style = TextStyle(fontSize = 16.sp, color = Ink))
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (ready) InkSecondary else com.subtrack.ui.theme.Warning,
                ),
            )
        }
        Spacer(Modifier.height(0.dp))
        Box(
            Modifier
                .size(44.dp, 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    if (!ready) SubTrackTheme.colors.segmentBg
                    else if (enabled) Accent
                    else SubTrackTheme.colors.segmentBg,
                ),
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color.White)
                    .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart),
            )
        }
    }
}
