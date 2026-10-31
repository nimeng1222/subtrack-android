package com.subtrack.ui.settings

import androidx.compose.foundation.background

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subtrack.domain.model.AppSettings
import com.subtrack.ui.openNotificationSettings
import com.subtrack.ui.theme.Accent
import com.subtrack.ui.theme.Ink
import com.subtrack.ui.theme.InkSecondary
import com.subtrack.ui.theme.SegmentBg
import com.subtrack.ui.theme.Type
import com.subtrack.ui.theme.Warning
import java.time.LocalTime

/**
 * 提醒与通知。
 *
 * 顺序按用户此刻要判断的事情排：先「什么时候提醒」（时间 + 提前天数），
 * 再「提醒现在到底能不能正常发出去」（通知权限 / 精确闹钟 / 电池优化），
 * 最后才是测试提醒这一个动作。厂商差异这类长说明收进帮助区，不占主视线。
 */
@Composable
internal fun ReminderSettingsScreen(
    settings: AppSettings,
    notificationsEnabled: Boolean,
    healthRefreshKey: Int,
    onUpdate: (AppSettings) -> Unit,
    onSendTestNotification: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showTime by remember { mutableStateOf(false) }
    var showOffsets by remember { mutableStateOf(false) }

    // healthRefreshKey 随回前台递增：用户在系统设置里改完权限返回时重读，不显示过时状态。
    val exactAlarmAllowed = remember(healthRefreshKey) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }
    val batteryIgnored = remember(healthRefreshKey) {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
    val allHealthy = notificationsEnabled && exactAlarmAllowed && batteryIgnored

    SettingsPage(title = "提醒与通知", onBack = onBack, listTag = "reminder-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "提醒时间",
                footer = "单条订阅可在编辑页覆盖这里的默认值。",
            ) {
                SettingsValueRow(
                    title = "每天提醒时刻",
                    value = "%02d:%02d".format(settings.reminderTime.hour, settings.reminderTime.minute),
                    tag = "reminder-time-row",
                    onClick = { showTime = true },
                )
                SettingsDivider(withIcon = false)
                SettingsValueRow(
                    title = "默认提前",
                    value = offsetsLabel(settings.defaultReminderOffsets),
                    tag = "reminder-offsets-row",
                    onClick = { showOffsets = true },
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsStatusCard(
                tone = if (allHealthy) Tone.Success else Tone.Warning,
                title = if (allHealthy) "提醒目前可以正常送达" else "部分系统设置会让提醒延迟或收不到",
                body = if (allHealthy) {
                    "通知、精确闹钟与电池优化都已就绪。关机或强制停止应用期间不会补发，打开应用时仍会看到到期提醒。"
                } else {
                    "下面标为“需处理”的项目会直接影响提醒能否准时出现。点右侧入口去系统设置，返回后状态会自动刷新。"
                },
            )
            Spacer(Modifier.height(14.dp))
            SettingsGroup(title = "提醒能力") {
                ReminderHealthRow(
                    title = "通知权限",
                    ok = notificationsEnabled,
                    detail = if (notificationsEnabled) "可以发通知" else "系统里关闭了本应用通知",
                    actionLabel = "去开启",
                    tag = "health-notifications",
                ) { openNotificationSettings(context) }
                SettingsDivider(withIcon = false)
                ReminderHealthRow(
                    title = "精确闹钟",
                    ok = exactAlarmAllowed,
                    detail = if (exactAlarmAllowed) "可以按时触发" else "只能近似时间触发",
                    actionLabel = "去授权",
                    tag = "health-exact-alarm",
                ) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        }
                    }
                }
                SettingsDivider(withIcon = false)
                ReminderHealthRow(
                    title = "电池优化",
                    ok = batteryIgnored,
                    detail = if (batteryIgnored) "不会被后台限制" else "可能被系统限制后台",
                    actionLabel = "去设置",
                    tag = "health-battery",
                ) { com.subtrack.ui.settings.openBatterySettings(context) }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "试一试") {
                SettingsButtonCluster {
                    SettingsButton(
                        text = "发一条测试提醒",
                        onClick = onSendTestNotification,
                        tag = "reminder-test-button",
                    )
                    if (!notificationsEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "通知权限未开启，测试提醒不会出现。请先在上面授权。",
                            style = Type.caption,
                            color = Warning,
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsHelp("帮助：厂商差异与送达边界") {
                SettingsParagraph("电池优化：在系统“所有应用”中找到“订阅台账”并选择“不优化”。不同厂商的入口名称可能不同（如“后台耗电管理”“自启动管理”）。")
                SettingsParagraph("提醒尽力送达：关机、省电模式、厂商后台限制都可能造成延迟。应用被强制停止期间不会补发；重新打开应用会把到点未发的提醒补出来。")
                SettingsParagraph("精确闹钟被系统收回（部分机型升级后会重置）时，提醒仍会发出，但可能晚几分钟到几十分钟。")
            }
        }
    }

    if (showTime) {
        TimeSheet(
            current = settings.reminderTime,
            onDismiss = { showTime = false },
            onConfirm = { picked ->
                onUpdate(settings.copy(reminderTime = picked))
                showTime = false
            },
        )
    }
    if (showOffsets) {
        OffsetsSheet(
            current = settings.defaultReminderOffsets,
            onDismiss = { showOffsets = false },
            onConfirm = { picked ->
                onUpdate(settings.copy(defaultReminderOffsets = picked))
                showOffsets = false
            },
        )
    }
}

/** 健康行：状态文字为主，颜色只作辅助；异常时右侧给出唯一动作。 */
@Composable
private fun ReminderHealthRow(
    title: String,
    ok: Boolean,
    detail: String,
    actionLabel: String,
    tag: String,
    onFix: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.callout, color = Ink, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            Text(
                if (ok) "正常 · $detail" else "需处理 · $detail",
                style = Type.caption,
                color = InkSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (!ok) {
            SettingsTextAction(actionLabel, onClick = onFix, tone = Tone.Warning)
        } else {
            Text("正常", style = Type.footnote.copy(fontWeight = FontWeight.Medium), color = com.subtrack.ui.theme.Success)
            Spacer(Modifier.width(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeSheet(current: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    var hour by remember { mutableIntStateOf(current.hour) }
    var minute by remember { mutableIntStateOf(current.minute) }
    val minutes = remember { (0..55 step 5).toList() }
    SettingsSheet(title = "提醒时刻", onDismiss = onDismiss, actions = {
        SettingsButton("保存", onClick = { onConfirm(LocalTime.of(hour, minute)) }, tag = "time-save")
        Spacer(Modifier.height(8.dp))
        SettingsButton("取消", onClick = onDismiss, style = ActionStyle.Secondary)
    }) {
        Text("小时", style = Type.caption, color = InkSecondary)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..23).forEach { h ->
                ChoiceChip(
                    label = "%02d".format(h),
                    selected = hour == h,
                    tag = "time-hour-$h",
                ) { hour = h }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("分钟", style = Type.caption, color = InkSecondary)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            minutes.forEach { m ->
                ChoiceChip(
                    label = "%02d".format(m),
                    selected = minute == m,
                    tag = "time-minute-$m",
                ) { minute = m }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "当前选择：%02d:%02d".format(hour, minute),
            style = Type.subhead.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
            modifier = Modifier.testTag("time-preview"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OffsetsSheet(current: List<Int>, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
    val aheadNow = current.filter { it > 0 }.maxOrNull()
    var ahead by remember { mutableStateOf<Int?>(aheadNow) }
    var onDueDay by remember { mutableStateOf(current.contains(0)) }
    val options = listOf(1, 2, 3, 5, 7, 14, 30)
    // 与旧行为一致：两项都关掉时至少保留"到期当天"，否则等于静默关掉了全部提醒。
    val picked = listOfNotNull(ahead, if (onDueDay || ahead == null) 0 else null).distinct().sortedDescending()
    SettingsSheet(title = "提前几天提醒", onDismiss = onDismiss, actions = {
        SettingsButton("保存", onClick = { onConfirm(picked) }, tag = "offset-save")
        Spacer(Modifier.height(8.dp))
        SettingsButton("取消", onClick = onDismiss, style = ActionStyle.Secondary)
    }) {
        Text("提前提醒", style = Type.caption, color = InkSecondary)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip(label = "不提前", selected = ahead == null, tag = "offset-none") { ahead = null }
            options.forEach { d ->
                ChoiceChip(label = "$d 天", selected = ahead == d, tag = "offset-$d") { ahead = d }
            }
        }
        Spacer(Modifier.height(14.dp))
        SettingsToggleRow(
            title = "到期当天再提醒一次",
            subtitle = "提前提醒之外，扣费当天早上再提醒一次",
            checked = onDueDay,
            tag = "offset-on-due-day",
            onToggle = { onDueDay = it },
        )
        Spacer(Modifier.height(10.dp))
        Text("保存后：${offsetsLabel(picked)}", style = Type.footnote, color = InkSecondary)
    }
}

/** 统一的选择 chip（时间、提前天数、币种等共用）。 */
@Composable
internal fun ChoiceChip(
    label: String,
    selected: Boolean,
    tag: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            // 触控目标 ≥ 48dp（视觉上是 44dp 的 chip，命中区按 48 算）
            .heightIn(min = 48.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent else SegmentBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Type.footnote.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) androidx.compose.ui.graphics.Color.White else Ink,
            maxLines = 1,
        )
    }
}
