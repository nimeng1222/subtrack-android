package com.subtrack.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.subtrack.AppContainer
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.ui.state.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 三个 SAF 启动器 + 一个 CSV 启动器，集中在这里让 MainActivity 只做导航。 */
class BackupLaunchers(
    val exportJson: () -> Unit,
    val exportCsv: () -> Unit,
    val importJson: () -> Unit,
    val importCsv: () -> Unit,
)

/**
 * 备份入口。四个动作都走 SAF，不申请任何存储权限（AC-53）。
 *
 * 抽成 Composable 工厂的原因是 `rememberLauncherForActivityResult` 只能在
 * Composable 作用域调用，但它和业务状态（订阅快照、设置、今天）耦合，
 * 放在 MainActivity 里会把导航逻辑挤到 400 行以上。
 */
@Composable
fun rememberBackupLaunchers(
    container: AppContainer,
    context: Context,
    scope: CoroutineScope,
    subscriptions: List<Subscription>,
    settings: AppSettings,
    today: LocalDate,
    onMessage: (UiMessage) -> Unit,
): BackupLaunchers {
    val backup = remember(container) { BackupController(container) }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { backup.exportJson(context, uri, subscriptions, settings) }
                .onFailure { onMessage(UiMessage.Toast("导出失败：${it.message}")) }
        }
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { backup.exportCsv(context, uri, subscriptions) }
                .onFailure { onMessage(UiMessage.Toast("导出失败：${it.message}")) }
        }
    }
    val importJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch { onMessage(backup.import(context, uri)) }
    }
    val importCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch { onMessage(backup.importCsv(context, uri, today)) }
    }

    return remember(backup, subscriptions, settings, today) {
        BackupLaunchers(
            exportJson = { exportJsonLauncher.launch(backup.jsonFileName(today)) },
            exportCsv = { exportCsvLauncher.launch(backup.csvFileName(today)) },
            importJson = { importJsonLauncher.launch(arrayOf("application/json", "text/plain")) },
            importCsv = {
                importCsvLauncher.launch(
                    arrayOf("text/csv", "text/comma-separated-values", "text/plain"),
                )
            },
        )
    }
}
