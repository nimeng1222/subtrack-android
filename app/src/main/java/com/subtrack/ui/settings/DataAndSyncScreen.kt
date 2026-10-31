package com.subtrack.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier

import androidx.compose.runtime.Composable
import com.subtrack.R

/**
 * 数据与同步。
 *
 * 备份与 CSV 是两个不同的东西（一个是完整还原包、一个是给表格软件看的清单），
 * 所以各有自己的导入/导出两条路，并在下方一句话说明差异，而不是四个含糊按钮。
 * 多设备同步是独立的管理页（首页 → 本页 → 同步管理），入口在这里只显示状态。
 */
@Composable
internal fun DataAndSyncScreen(
    sync: SyncSummary,
    onOpenSync: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    onBack: () -> Unit,
) {
    val syncTone = when {
        sync.initError != null -> Tone.Danger
        sync.active && sync.conflicts > 0 -> Tone.Warning
        sync.active -> Tone.Success
        else -> Tone.Muted
    }
    SettingsPage(title = "数据与同步", onBack = onBack, listTag = "data-settings-list") {
        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "备份",
                footer = "备份包含订阅、流水与分类；导入时自动跳过已存在的条目，中途失败会整体回滚。",
            ) {
                SettingsNavRow(
                    icon = R.drawable.ic_receipt,
                    title = "导出备份",
                    status = "含订阅与流水",
                    tag = "action-export-json",
                    onClick = onExportJson,
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = R.drawable.ic_import,
                    title = "导入备份",
                    status = "先预览再导入",
                    tag = "action-import-json",
                    onClick = onImportJson,
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "CSV",
                footer = "CSV 适合在 Excel 里看；自定义周期等参数请用备份（JSON）往返，CSV 会丢失这些细节。",
            ) {
                SettingsNavRow(
                    icon = R.drawable.ic_grid,
                    title = "导出 CSV",
                    status = "可在 Excel 打开",
                    tag = "action-export-csv",
                    onClick = onExportCsv,
                )
                SettingsDivider()
                SettingsNavRow(
                    icon = R.drawable.ic_import,
                    title = "导入 CSV",
                    status = "跳过已存在条目",
                    tag = "action-import-csv",
                    onClick = onImportCsv,
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsGroup(title = "多设备同步", footer = "默认不上传。创建或加入账本后，才在你主动确认时上传。") {
                SettingsNavRow(
                    icon = R.drawable.ic_cloud,
                    title = "同步管理",
                    status = SettingsSummaries.syncLine(
                        active = sync.active,
                        pendingUploads = sync.pendingUploads,
                        conflicts = sync.conflicts,
                        initError = sync.initError,
                        configured = sync.configured,
                    ),
                    tone = syncTone,
                    tag = "sync-open",
                    onClick = onOpenSync,
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            SettingsHelp("帮助：导入会改什么") {
                SettingsParagraph("导入备份不会覆盖已有条目：同 ID 一律跳过，所以重复导入同一份文件是安全的。")
                SettingsParagraph("导入在单个事务里完成；如果中途失败，本机数据回到导入前的状态，不会留下半份数据。")
                SettingsParagraph("CSV 导入按行解析，金额与日期不合法的行会被跳过并汇总提示。")
            }
        }
    }
}
