package com.subtrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.ThemeMode
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type

/** 显示与外观：跟随系统 / 浅色 / 深色。三种模式共用一套主题 Token，浅深一致。 */
@Composable
internal fun AppearanceSettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
    val labels = listOf("跟随系统", "浅色", "深色")
    SettingsPage(title = "显示与外观", onBack = onBack, listTag = "appearance-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "外观模式") {
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                    SettingsSegmented(
                        options = labels,
                        selected = modes.indexOf(settings.themeMode).coerceAtLeast(0),
                        onSelect = { index -> onUpdate(settings.copy(themeMode = modes[index])) },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (settings.themeMode) {
                            ThemeMode.SYSTEM -> "跟随系统设置，系统切换深色时应用一起切换。"
                            ThemeMode.LIGHT -> "始终使用浅色，忽略系统深色设置。"
                            ThemeMode.DARK -> "始终使用深色，适合夜间查看。"
                        },
                        style = Type.caption.copy(lineHeight = 17.sp),
                        color = InkSecondary,
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsHelp("帮助：界面一致性与字体") {
                SettingsParagraph("设置页与首页、统计页共用同一套颜色与字号：浅色与深色下强调色都是蓝色，卡片、分组与分隔线不换皮肤。")
                SettingsParagraph("调大系统字体后，设置行会换行显示，右侧状态最多两行，标题不会被挤压；触控目标不小于 48dp。")
            }
        }
    }
}
