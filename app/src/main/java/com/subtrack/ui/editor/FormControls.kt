package com.subtrack.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.FieldBg
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.InkTertiary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.SubTrackTheme
import com.subtrack.ui.theme.Success

/** iOS 表单分组：浅灰底、圆角 14。 */
@Composable
internal fun FieldSurface(
    padding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FieldBg)
            .padding(padding),
        content = content,
    )
}

@Composable
internal fun androidx.compose.foundation.layout.RowScope.SegmentCell(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) SubTrackTheme.colors.sheetBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 15.sp,
                color = if (active) Ink else InkSecondary,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/** iOS 风格开关：51×31、绿色。 */
@Composable
internal fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (checked) Success else SegmentBg)
            .clickable { onChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .size(27.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SubTrackTheme.colors.sheetBg)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}

/** 金额输入：¥ + 34/600 tabular 数字，空态显示 0.00。 */
@Composable
internal fun AmountField(value: String, currency: String = "CNY", onChange: (String) -> Unit) {
    FieldSurface {
        Text("金额（$currency）", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (currency == "CNY") "¥" else currency, style = TextStyle(fontSize = 30.sp, color = Ink, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(3.dp))
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = TextStyle(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    letterSpacing = (-0.8).sp,
                    fontFeatureSettings = "tnum",
                ),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.weight(1f).testTag("editor-amount"),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "0.00",
                            style = TextStyle(
                                fontSize = 34.sp, color = InkTertiary,
                                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp,
                            ),
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/** 服务名称输入。 */
@Composable
internal fun NameField(value: String, onChange: (String) -> Unit) {
    FieldSurface {
        Text("服务名称", style = TextStyle(fontSize = 13.sp, color = InkSecondary))
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 20.sp, color = Ink, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.fillMaxWidth().testTag("editor-name"),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("如 ChatGPT Plus", style = TextStyle(fontSize = 20.sp, color = InkTertiary))
                }
                inner()
            },
        )
    }
}
