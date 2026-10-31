package com.subtrack.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.R
import com.subtrack.ui.theme.BlueSoft
import com.subtrack.ui.theme.Chevron
import com.subtrack.ui.theme.CyanSoft
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Mint
import com.subtrack.ui.theme.PageBg
import com.subtrack.ui.theme.Separator
import com.subtrack.ui.theme.Sky
import com.subtrack.ui.theme.SubTrackTheme

/**
 * 页面环境色场：浅底 + 4 个柔和色晕，营造"有光感"的玻璃背景。
 * 用 Canvas 画径向渐变；不做真正的 backdrop blur（代价高且低版本不可用）。
 */
@Composable
fun AmbientBackground(content: @Composable BoxScope.() -> Unit) {
    // 颜色在 Composable 作用域先取出来，Canvas 的 DrawScope 里不能再调 @Composable
    val bg = PageBg
    val sky = Sky
    val blueSoft = BlueSoft
    val mint = Mint
    val cyanSoft = CyanSoft
    val isDark = SubTrackTheme.colors.isDark
    Box(Modifier.fillMaxSize().background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            fun blob(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(cx, cy),
                )
            }
            // 深色下色晕要更收敛，否则会像"脏灰"而不是"光"
            val k = if (isDark) 0.55f else 1f
            blob(sky, size.width * 0.12f, -size.height * 0.02f, size.width * 0.80f, 0.32f * k)
            blob(blueSoft, size.width * 0.96f, size.height * 0.04f, size.width * 0.64f, 0.28f * k)
            blob(mint, -size.width * 0.06f, size.height * 0.56f, size.width * 0.62f, 0.18f * k)
            blob(cyanSoft, size.width * 1.02f, size.height * 0.92f, size.width * 0.68f, 0.24f * k)
        }
        // 顶部再压一层白，让状态栏区域更亮
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (isDark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.28f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        content()
    }
}

/**
 * 圆角矩形原生阴影，画在本组件 bounds 下方（不裁剪内容）。
 *
 * 注意：不要用 `Modifier.shadow(clip=false) + clip()` —— 本机实测会在卡片内部
 * 渲染出一块亮色矩形（模糊层与裁剪层 bounds 不一致），这是 v0.2.1 截图的玻璃卡瑕疵根因。
 */
fun Modifier.nativeShadow(
    corner: Dp,
    blur: Dp = 18.dp,
    dy: Dp = 8.dp,
    color: Int = 0x140F172A,
): Modifier = drawBehind {
    val paint = Paint().apply {
        this.color = Color.Transparent
        asFrameworkPaint().apply {
            this.color = 0
            setShadowLayer(blur.toPx(), 0f, dy.toPx(), color)
        }
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(
            0f, 0f, size.width, size.height,
            corner.toPx(), corner.toPx(), paint.asFrameworkPaint(),
        )
    }
}

/** 玻璃卡：白色半透明 + 顶部高光 + 内描边 + 柔和投影。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    padding: PaddingValues = PaddingValues(18.dp),
    alpha: Float = 0.72f,
    elevation: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier = modifier
            .nativeShadow(corner = corner, blur = elevation, dy = 8.dp)
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    if (SubTrackTheme.colors.isDark) {
                        // 深色下统一不透明度：不同 alpha 会让卡片深浅不一，像"用了两种卡片"
                        listOf(
                            SubTrackTheme.colors.glass,
                            SubTrackTheme.colors.glass.copy(alpha = 0.94f),
                        )
                    } else {
                        listOf(
                            SubTrackTheme.colors.glass.copy(alpha = alpha),
                            SubTrackTheme.colors.glass.copy(alpha = alpha * 0.82f),
                        )
                    },
                ),
                shape = shape,
            )
            .border(1.dp, SubTrackTheme.colors.glassStroke.copy(alpha = if (SubTrackTheme.colors.isDark) 0.10f else 0.92f), shape)
            .padding(padding),
        content = content,
    )
}

/** 分类徽标：圆角 8 系、同色系 13% 淡底 + 全彩图形（iOS 设置页语言）。 */
@Composable
fun CategoryBadge(iconRes: Int, color: Color, size: Dp = 30.dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, SubTrackTheme.colors.glassStroke.copy(alpha = if (SubTrackTheme.colors.isDark) 0.08f else 0.66f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

/** 分区标题，13 / secondary；可带右侧入口。 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = InkSecondary),
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 金额文本：tabular 数字、右对齐。 */
@Composable
fun AmountText(
    text: String,
    size: Int = 17,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Ink,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontSize = size.sp,
            lineHeight = (size * 1.18f).sp,
            fontWeight = weight,
            letterSpacing = (-0.3).sp,
            fontFeatureSettings = "tnum",
            color = color,
        ),
    )
}

/** 底部渐隐，避免浮动 Tab 硬切列表。 */
@Composable
fun BottomFade(modifier: Modifier = Modifier, height: Int = 150) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, PageBg.copy(alpha = 0.88f), PageBg.copy(alpha = 0.99f)),
                ),
            ),
    )
}

/** 列表内缩分隔线：与文字左对齐，不通栏。 */
@Composable
fun InsetDivider(startIndent: Int = 56) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent.dp)
            .height(0.5.dp)
            .background(Separator),
    )
}

/** 行尾 chevron。 */
@Composable
fun ChevronIcon(size: Dp = 13.dp) {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = Chevron,
    )
}
