package com.subtrack.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.R
import com.subtrack.ui.components.AmbientBackground
import com.subtrack.ui.components.CategoryBadge
import com.subtrack.ui.components.ChevronIcon
import com.subtrack.ui.components.DismissibleOverlay
import com.subtrack.ui.components.GlassCard
import com.subtrack.ui.components.InsetDivider
import com.subtrack.ui.components.SectionHeader
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Danger
import com.subtrack.ui.theme.FieldBg
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.InkTertiary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.Separator
import com.subtrack.ui.theme.SheetBg
import com.subtrack.ui.theme.SubTrackTheme
import com.subtrack.ui.theme.Success
import com.subtrack.ui.theme.Type
import com.subtrack.ui.theme.Warning

/**
 * 设置模块的统一组件集。
 *
 * 改版前的设置页一半是自绘行、一半是裸用的 Material 组件，同一层级的输入框圆角/底色/字号
 * 都不一样，读起来像两个人写的。这里把行、开关、值、输入框、按钮、状态提示收敛到一套
 * 尺寸与配色上，所有设置页只允许用这里的组件，避免再次出现两套皮肤。
 *
 * 尺寸约定（改一处即全部生效）：
 * - 页面左右留白 20dp，分组圆角 18dp，分组内行左右 16/14dp；
 * - 图标 30dp（与首页分类徽标同尺寸），文字 16sp，状态 13sp 右对齐最多两行；
 * - 行最小高度 56dp，可点击行整行命中，触控目标不超过 48dp 的下限。
 */
internal object SettingsMetrics {
    val pagePadding = 20.dp
    val groupCorner = 18.dp
    val rowIcon = 30.dp
    val rowMinHeight = 56.dp
    /** 有图标的行，分隔线缩进到标题左缘：16 左内边距 + 30 图标 + 12 间距。 */
    val iconRowIndent = 58
    val textRowIndent = 16
    val statusMaxWidth = 168.dp
}

/** 状态语气。颜色只作辅助，文字本身必须能独立说明状态。 */
enum class Tone { Neutral, Success, Warning, Danger, Muted }

@Composable
internal fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Neutral -> InkSecondary
    Tone.Success -> Success
    Tone.Warning -> Warning
    Tone.Danger -> Danger
    Tone.Muted -> InkTertiary
}

/**
 * 功能页骨架：统一标题、统一返回、**只有一层滚动容器**（LazyColumn）。
 *
 * 页内容整个作为 LazyColumn 的第一个 item 里的一个 Column：这样页内所有行都参与组合，
 * 无障碍焦点、测试定位与"从功能页返回"都稳定；也彻底避免把 verticalScroll 表单塞进
 * LazyColumn item 里造成的嵌套滚动（改版前同步区就是这个问题）。
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    listState: LazyListState = rememberLazyListState(),
    listTag: String? = null,
    bottomContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AmbientBackground {
        Column(Modifier.fillMaxSize()) {
            // 标题与返回固定在顶部：长页面（联网与 AI、同步管理）滚到底部时也要能一步返回，
            // 不能把返回按钮一起滚走。
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(start = SettingsMetrics.pagePadding, end = SettingsMetrics.pagePadding, top = 8.dp),
            ) {
                SettingsTopBar(title, onBack)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding()
                    .then(if (listTag != null) Modifier.testTag(listTag) else Modifier),
                state = listState,
                contentPadding = PaddingValues(
                    start = SettingsMetrics.pagePadding,
                    end = SettingsMetrics.pagePadding,
                    top = 10.dp,
                    bottom = 40.dp,
                ),
            ) {
                item("settings-page-content") {
                    Column(Modifier.fillMaxWidth()) { content() }
                }
            }
            // 底部 Tab 栏占据自己的高度，而不是浮在内容上：内容不会被遮住，
            // 也不会出现"最后一行被渐隐吃掉"的情况。
            bottomContent?.invoke(this)
        }
    }
}

@Composable
private fun SettingsTopBar(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier
                    // 触控目标 48dp（图形 19dp，圆形底 40dp 视觉）
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag("settings-back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "返回设置",
                    tint = Ink,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            style = Type.title2,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onBack == null) Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun SettingsSectionLabel(text: String) {
    SectionHeader(text)
    Spacer(Modifier.height(8.dp))
}

/**
 * 分组卡片。[title] 为分组名，[footer] 是紧跟卡片的一行说明。
 * 组内分隔线统一由行组件自己画，调用方只负责把行按顺序放进来。
 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) SettingsSectionLabel(title)
    GlassCard(padding = PaddingValues(0.dp), corner = SettingsMetrics.groupCorner, modifier = modifier) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
    if (footer != null) {
        Spacer(Modifier.height(7.dp))
        Text(
            footer,
            style = Type.caption.copy(lineHeight = 17.sp),
            color = InkSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    Spacer(Modifier.height(22.dp))
}

/** 组内分隔线：有图标缩进到标题左缘，无图标缩进 16dp。 */
@Composable
internal fun SettingsDivider(withIcon: Boolean = true) {
    InsetDivider(if (withIcon) SettingsMetrics.iconRowIndent else SettingsMetrics.textRowIndent)
}

/**
 * 导航行：图标 + 标题 + 右侧状态 + 箭头。状态最多两行，宽度有上限，
 * 所以长标题（中文长名字/大字体）只会换行，不会被状态挤没。
 */
@Composable
internal fun SettingsNavRow(
    icon: Int,
    title: String,
    status: String,
    tone: Tone = Tone.Neutral,
    tag: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsMetrics.rowMinHeight)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标颜色只表达"这是设置入口"，不随状态变：否则同一组里会一半蓝一半灰，像两套组件。
        CategoryBadge(icon, Accent, SettingsMetrics.rowIcon)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = Type.callout,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            status,
            style = Type.footnote,
            color = toneColor(tone),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = SettingsMetrics.statusMaxWidth),
        )
        Spacer(Modifier.width(6.dp))
        ChevronIcon()
    }
}

/** 开关行：整行可点，开关只做视觉，避免"点行无效、只有开关生效"的割裂。 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    tag: String? = null,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.callout, color = if (enabled) Ink else InkTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = Type.caption.copy(lineHeight = 17.sp), color = InkSecondary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                checkedBorderColor = Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = SegmentBg,
                uncheckedBorderColor = Separator,
            ),
        )
    }
}

/** 值行：标题 + 右值（可点进入编辑），没有箭头表示"只读"。 */
@Composable
internal fun SettingsValueRow(
    title: String,
    value: String,
    tag: String? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.callout, color = Ink, modifier = Modifier.weight(1f), maxLines = 2)
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            style = Type.callout,
            color = valueColor ?: InkSecondary,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = SettingsMetrics.statusMaxWidth),
        )
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            ChevronIcon()
        }
    }
}

enum class ActionStyle { Primary, Secondary, Danger }

/** 主/次/危险按钮。三者尺寸一致，颜色与层级不同；危险按钮永远不放在主操作位。 */
@Composable
internal fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ActionStyle = ActionStyle.Primary,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val shape = RoundedCornerShape(13.dp)
    // 次按钮用"浅强调底 + 强调色文字"，与输入框（浅灰底 + 灰描边 + 左对齐占位符）区分开，
    // 否则一排次按钮读起来像一排空输入框。
    val background = when (style) {
        ActionStyle.Primary -> if (enabled) Accent else Accent.copy(alpha = 0.14f)
        ActionStyle.Secondary -> if (enabled) Accent.copy(alpha = 0.10f) else Color.Transparent
        ActionStyle.Danger -> if (enabled) Danger.copy(alpha = 0.12f) else Danger.copy(alpha = 0.06f)
    }
    val textColor = when (style) {
        ActionStyle.Primary -> if (enabled) Color.White else Accent.copy(alpha = 0.55f)
        ActionStyle.Secondary -> if (enabled) Accent else InkTertiary
        ActionStyle.Danger -> if (enabled) Danger else Danger.copy(alpha = 0.5f)
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clip(shape)
            .background(background)

            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.subhead.copy(fontWeight = FontWeight.SemiBold), color = textColor, textAlign = TextAlign.Center)
    }
}

/** 行内文字动作：用于"去开启""清除"这类次操作，最小高度仍保证 48dp。 */
@Composable
internal fun SettingsTextAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tone: Tone = Tone.Neutral,
    tag: String? = null,
) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Type.footnote.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) toneColor(tone) else InkTertiary,
            maxLines = 1,
        )
    }
}

/** 按钮排：主操作占满一行，次操作并排在下一行，避免一排蓝色 TextButton 当交互。 */
@Composable
internal fun SettingsButtonCluster(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        content()
    }
}

/** 与行左内边距对齐的内容块（按钮、字段说明等直接放进分组卡片时用）。 */
@Composable
internal fun SettingsPaddedContent(
    horizontal: Dp = 16.dp,
    top: Dp = 4.dp,
    bottom: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = top, bottom = bottom)) {
        content()
    }
}

/**
 * 统一输入框：标签在上、错误紧贴字段、助手说明在下。
 * 不用裸的 OutlinedTextField——它的描边/内边距与本应用的自绘行不是一套语言。
 */
@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int = 2048,
    enabled: Boolean = true,
    tag: String? = null,
    singleLine: Boolean = true,
) {
    val dangerColor = Danger
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = Type.caption, color = InkSecondary)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) FieldBg else SegmentBg.copy(alpha = 0.5f))
                .border(1.dp, if (error != null) dangerColor else Separator, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= maxLength) onValueChange(it) },
                enabled = enabled,
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = Type.callout.copy(color = if (enabled) Ink else InkTertiary),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = Type.callout, color = InkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                },
            )
        }
        if (error != null) {
            Spacer(Modifier.height(5.dp))
            Text(error, style = Type.caption, color = dangerColor)
        } else if (helper != null) {
            Spacer(Modifier.height(5.dp))
            Text(helper, style = Type.caption.copy(lineHeight = 17.sp), color = InkSecondary)
        }
    }
}

/** 状态条：说明当前能力是否可用，并给出唯一动作。 */
@Composable
internal fun SettingsStatusCard(
    tone: Tone,
    title: String,
    body: String,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    GlassCard(padding = PaddingValues(16.dp), corner = SettingsMetrics.groupCorner) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(toneColor(tone)))
            Spacer(Modifier.width(9.dp))
            Text(title, style = Type.subhead.copy(fontWeight = FontWeight.SemiBold), color = Ink, modifier = Modifier.weight(1f))
            action?.let { Row(horizontalArrangement = Arrangement.End, content = it) }
        }
        Spacer(Modifier.height(7.dp))
        Text(body, style = Type.footnote.copy(lineHeight = 19.sp), color = InkSecondary)
    }
}

/**
 * 可折叠的帮助区：详细技术说明（厂商省电差异、协议字段、过期口径）默认收起，
 * 主区域只留用户此刻要判断的那句话。
 */
@Composable
internal fun SettingsHelp(title: String = "帮助与说明", content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = Type.footnote.copy(fontWeight = FontWeight.Medium), color = InkSecondary, modifier = Modifier.weight(1f))
            Text(if (open) "收起" else "展开", style = Type.caption, color = InkTertiary)
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                content()
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
internal fun SettingsParagraph(text: String) {
    Text(
        text,
        style = Type.footnote.copy(lineHeight = 20.sp),
        color = InkSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** 分段选择（外观模式等），与设置里的 chip 视觉统一。 */
@Composable
internal fun SettingsSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(SegmentBg)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Accent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Type.footnote.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (active) Color.White else InkSecondary,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 统一底部弹层：拖拽把手 + 标题 + 单一滚动内容，键盘弹出时整体上移。 */
@Composable
internal fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 弹层打开时，系统返回应先关弹层，而不是退出整页。
    androidx.activity.compose.BackHandler(enabled = true, onBack = onDismiss)
    DismissibleOverlay(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(SheetBg)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(InkTertiary))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Type.title3, color = Ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                SettingsTextAction("关闭", onClick = onDismiss)
            }
            Spacer(Modifier.height(6.dp))
            // 内容区限高 + 滚动（不用 weight：高度是 wrap 时 weight 会把内容量成 0）。
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) { content() }
            if (actions != null) {
                // 主/次操作固定在弹层底部：内容再长也不会把"保存"挤出屏幕或被裁掉。
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth()) { actions() }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 分组内的窄间距。 */
@Composable
internal fun SettingsGap(height: Dp = 10.dp) = Spacer(Modifier.height(height))
