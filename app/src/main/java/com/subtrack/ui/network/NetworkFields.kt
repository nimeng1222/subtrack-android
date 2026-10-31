package com.subtrack.ui.network

import androidx.compose.runtime.Composable
import com.subtrack.ui.settings.SettingsTextField

/**
 * 联网相关弹窗里的通用输入框。
 *
 * 改版后统一走设置模块的输入框组件：同一圆角、同一底色、同一错误位置，
 * 不再让弹窗里的字段和设置页的字段长得不一样。
 */
@Composable
internal fun ConfigField(
    label: String,
    value: String,
    tag: String = "",
    secret: Boolean = false,
    change: (String) -> Unit,
) {
    SettingsTextField(
        value = value,
        onValueChange = { if (it.length <= 2048) change(it) },
        label = label,
        secret = secret,
        tag = tag.ifEmpty { null },
    )
}
