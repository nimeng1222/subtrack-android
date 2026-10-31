package com.subtrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.subtrack.core.money.Money
import com.subtrack.core.money.MoneyInput
import com.subtrack.domain.model.AppSettings
import com.subtrack.ui.network.AnalysisDialog
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.Type

/**
 * 预算与统计：月度预算、涨价预警、订阅体检，以及本地方案比较。
 *
 * 本地方案比较**刻意留在这里**而不是联网页：它只在本机算，不需要任何服务，
 * 放在 AI 配置里会让人以为要先连上模型才能用。
 */
@Composable
internal fun BudgetStatsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var showBudget by remember { mutableStateOf(false) }
    var analysisOpen by remember { mutableStateOf(false) }

    SettingsPage(title = "预算与统计", onBack = onBack, listTag = "budget-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "月度预算",
                footer = "超支时在统计页提示；不设置就不会提示。",
            ) {
                SettingsValueRow(
                    title = "每月预算上限",
                    value = settings.monthlyBudgetMinor?.let { Money.format(it) } ?: "未设置",
                    tag = "budget-row",
                    onClick = { showBudget = true },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "分析与提醒") {
                SettingsToggleRow(
                    title = "涨价预警",
                    subtitle = "对比最近一次实付金额，标出涨价或降价的订阅",
                    checked = settings.priceWatchEnabled,
                    tag = "toggle-price-watch",
                    onToggle = { onUpdate(settings.copy(priceWatchEnabled = it)) },
                )
                SettingsDivider(withIcon = false)
                SettingsToggleRow(
                    title = "订阅体检",
                    subtitle = "找出长期未确认、单条占比过高、年付即将续费的条目",
                    checked = settings.healthCheckEnabled,
                    tag = "toggle-health-check",
                    onToggle = { onUpdate(settings.copy(healthCheckEnabled = it)) },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "本地方案比较",
                footer = "全部在本机计算，不联网、不修改订阅。",
            ) {
                SettingsValueRow(
                    title = "方案比较与支出模拟",
                    value = "本机计算",
                    tag = "local-analysis-row",
                    onClick = { analysisOpen = true },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsHelp("帮助：这些开关影响什么") {
                SettingsParagraph("月度预算只影响统计页的超支提示，不会阻止记账，也不会改动任何订阅金额。")
                SettingsParagraph("涨价预警需要至少两笔实付流水才能比较出变化；刚录入的订阅不会立刻提示。")
                SettingsParagraph("订阅体检只看本机数据：长期未确认的待处理项、单条占比过高、年付临近续费。")
            }
        }
    }

    if (showBudget) {
        BudgetSheet(
            currentMinor = settings.monthlyBudgetMinor,
            onDismiss = { showBudget = false },
            onSave = { minor ->
                onUpdate(settings.copy(monthlyBudgetMinor = minor))
                showBudget = false
            },
            onClear = {
                onUpdate(settings.copy(monthlyBudgetMinor = null))
                showBudget = false
            },
        )
    }
    if (analysisOpen) AnalysisDialog(onClose = { analysisOpen = false })
}

@Composable
private fun BudgetSheet(
    currentMinor: Long?,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    onClear: () -> Unit,
) {
    // 独立草稿：输入过程中不写设置库，避免每敲一个字符就持久化一次。
    var input by remember {
        mutableStateOf(currentMinor?.let { "${it / 100}" } ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSheet(title = "月度预算", onDismiss = onDismiss, actions = {
        SettingsButton(
            text = "保存预算",
            onClick = {
                val minor = MoneyInput.price(input)
                if (minor == null || minor <= 0) {
                    error = "请输入有效预算（不超过 100 亿元），原预算已保留"
                } else {
                    onSave(minor)
                }
            },
            tag = "budget-save",
        )
        if (currentMinor != null) {
            Spacer(Modifier.height(8.dp))
            SettingsButton("清除预算", onClick = onClear, style = ActionStyle.Secondary, tag = "budget-clear")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "当前：${currentMinor?.let { Money.format(it) } ?: "未设置"}",
            style = Type.caption,
            color = InkSecondary,
            modifier = Modifier.testTag("budget-current"),
        )
    }) {
        SettingsTextField(
            value = input,
            onValueChange = { raw -> input = raw.filter { it.isDigit() || it == '.' }; error = null },
            label = "每月预算（元）",
            placeholder = "例如 300",
            helper = "只用于统计页的超支提示，不影响记账。",
            error = error,
            keyboardType = KeyboardType.Decimal,
            tag = "budget-input",
        )
    }
}
