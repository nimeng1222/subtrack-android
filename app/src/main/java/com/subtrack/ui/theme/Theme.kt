package com.subtrack.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.subtrack.R

/**
 * 设计 Token。
 *
 * 用 [SubTrackColors] + CompositionLocal 而不是直接读全局常量，
 * 是为了让深色模式成为"换一套 Token"而不是"每个页面加 if (isDark)"。
 * 所有 UI 一律通过 `SubTrackTheme.colors.xxx` 取色，禁止再写 `Color(0x...)`。
 */
@Immutable
data class SubTrackColors(
    val pageBg: Color,
    val sky: Color,
    val blueSoft: Color,
    val mint: Color,
    val cyanSoft: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val chevron: Color,
    val separator: Color,
    /** 玻璃卡填充（浅色下白、深色下浅白），带 alpha 由使用处决定。 */
    val glass: Color,
    /** 玻璃卡内描边。 */
    val glassStroke: Color,
    /** 输入框/分组的浅底（等价 iOS 的 secondarySystemBackground）。 */
    val fieldBg: Color,
    /** 分段控件底。 */
    val segmentBg: Color,
    /** Sheet / 弹层的不透明底。 */
    val sheetBg: Color,
    /** 次级表面（Tab、提示条）。 */
    val surfaceSecondary: Color,
    val isDark: Boolean,
)

private val LightColors = SubTrackColors(
    pageBg = Color(0xFFF2F4F8),
    sky = Color(0xFF7DD3FC),
    blueSoft = Color(0xFF93C5FD),
    mint = Color(0xFF99F6E4),
    cyanSoft = Color(0xFFBAE6FD),
    ink = Color(0xFF0B0F14),
    inkSecondary = Color(0xFF86868B),
    inkTertiary = Color(0xFFAEAEB2),
    accent = Color(0xFF0A7CFF),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9500),
    danger = Color(0xFFFF3B30),
    chevron = Color(0xFFC4C4C9),
    separator = Color(0x1F3C3C43),
    glass = Color(0xFFFFFFFF),
    glassStroke = Color(0xFFFFFFFF),
    fieldBg = Color(0xFFF7F8FB),
    segmentBg = Color(0xFFEFEFF4),
    sheetBg = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFF9F9FB),
    isDark = false,
)

/**
 * 深色 Token。对齐 DIRECTION.md §5 与 §8：深色下不反相、不发光，
 * 强调色从 #007AFF 换 #0A84FF（深色背景上对比度更好）。
 */
private val DarkColors = SubTrackColors(
    pageBg = Color(0xFF000000),
    sky = Color(0xFF0C4A6E),
    blueSoft = Color(0xFF1E3A8A),
    mint = Color(0xFF134E4A),
    cyanSoft = Color(0xFF155E75),
    ink = Color(0xFFF5F5F7),
    inkSecondary = Color(0xFF98989F),
    inkTertiary = Color(0xFF636366),
    accent = Color(0xFF0A84FF),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
    chevron = Color(0xFF5A5A5E),
    separator = Color(0x33FFFFFF),
    // 深色玻璃：用很淡的白叠在黑底上，模拟"亚克力"而不是灰卡
    glass = Color(0xFF1C1C1E),
    glassStroke = Color(0x1FFFFFFF),
    fieldBg = Color(0xFF1C1C1E),
    segmentBg = Color(0xFF2C2C2E),
    sheetBg = Color(0xFF1C1C1E),
    surfaceSecondary = Color(0xFF2C2C2E),
    isDark = true,
)

val LocalSubTrackColors = staticCompositionLocalOf { LightColors }

/** 取色入口。UI 层统一 `SubTrackTheme.colors.ink` 这样用。 */
object SubTrackTheme {
    val colors: SubTrackColors
        @Composable get() = LocalSubTrackColors.current
}

// ── 兼容层：旧的全局颜色名 → 主题感知的 getter ───────────────
// 这样 70 处 import 不用改，但取值会自动跟随当前主题（含深色）。
// 新增代码请直接用 SubTrackTheme.colors.xxx。

val PageBg: Color @Composable get() = SubTrackTheme.colors.pageBg
val Sky: Color @Composable get() = SubTrackTheme.colors.sky
val BlueSoft: Color @Composable get() = SubTrackTheme.colors.blueSoft
val Mint: Color @Composable get() = SubTrackTheme.colors.mint
val CyanSoft: Color @Composable get() = SubTrackTheme.colors.cyanSoft
val Ink: Color @Composable get() = SubTrackTheme.colors.ink
val InkSecondary: Color @Composable get() = SubTrackTheme.colors.inkSecondary
val InkTertiary: Color @Composable get() = SubTrackTheme.colors.inkTertiary
val Accent: Color @Composable get() = SubTrackTheme.colors.accent
val Success: Color @Composable get() = SubTrackTheme.colors.success
val Warning: Color @Composable get() = SubTrackTheme.colors.warning
val Danger: Color @Composable get() = SubTrackTheme.colors.danger
val Chevron: Color @Composable get() = SubTrackTheme.colors.chevron
val Separator: Color @Composable get() = SubTrackTheme.colors.separator
val GlassFill: Color @Composable get() = SubTrackTheme.colors.glass
val GlassStroke: Color @Composable get() = SubTrackTheme.colors.glassStroke
val FieldBg: Color @Composable get() = SubTrackTheme.colors.fieldBg
val SegmentBg: Color @Composable get() = SubTrackTheme.colors.segmentBg
val SheetBg: Color @Composable get() = SubTrackTheme.colors.sheetBg
val SurfaceSecondary: Color @Composable get() = SubTrackTheme.colors.surfaceSecondary

// ── 字体：Inter 子集（含 tnum 等宽数字）─────────────────────────
private val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** 表格数字（等宽）——金额右对齐不跳动。 */
const val TABULAR = "tnum"

private fun num(size: Int, line: Int, weight: FontWeight, spacing: Double = -0.3) = TextStyle(
    // 拉丁与数字走 Inter，中文自动回落到系统 Noto Sans SC（Inter 不含 CJK）
    fontFamily = InterFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
    fontFeatureSettings = TABULAR,
)

/** 字号阶梯，权重只用 400 / 600 / 700。 */
object Type {
    val largeTitle = num(34, 41, FontWeight.Bold, -0.7)
    val title2 = num(22, 28, FontWeight.Bold, -0.5)
    val title3 = num(20, 25, FontWeight.SemiBold, -0.4)
    val headline = num(17, 22, FontWeight.SemiBold, -0.2)
    val body = num(17, 22, FontWeight.Normal, -0.2)
    val callout = num(16, 21, FontWeight.Normal, -0.1)
    val subhead = num(15, 20, FontWeight.Normal, 0.0)
    val footnote = num(13, 18, FontWeight.Normal, 0.0)
    val caption = num(12, 16, FontWeight.Normal, 0.0)
    val caption2 = num(11, 14, FontWeight.Medium, 0.1)
    val tabLabel = num(10, 12, FontWeight.Medium, 0.1)

    val amountHero = num(34, 38, FontWeight.SemiBold, -0.8)
    val amountStat = num(19, 23, FontWeight.SemiBold, -0.4)
    val amountRow = num(17, 22, FontWeight.Normal, -0.2)

    /** 供 Compose 组件直接用（如 Text 的 style 参数）。 */
    fun mono(size: Int, weight: FontWeight = FontWeight.Normal) = num(size, (size * 1.18f).toInt(), weight)
}

private val SubTrackTypography = Typography(
    displayLarge = Type.largeTitle,
    headlineMedium = Type.title2,
    titleMedium = Type.headline,
    bodyLarge = Type.body,
    bodyMedium = Type.subhead,
    bodySmall = Type.footnote,
    labelSmall = Type.caption2,
)

private fun scheme(c: SubTrackColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accent, onPrimary = Color.White,
        background = c.pageBg, onBackground = c.ink,
        surface = c.glass, onSurface = c.ink,
        secondary = c.inkSecondary, error = c.danger,
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = Color.White,
        background = c.pageBg, onBackground = c.ink,
        surface = Color.White, onSurface = c.ink,
        secondary = c.inkSecondary, error = c.danger,
    )
}

@Composable
fun SubTrackTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalSubTrackColors provides colors) {
        MaterialTheme(
            colorScheme = scheme(colors),
            typography = SubTrackTypography,
            content = content,
        )
    }
}
