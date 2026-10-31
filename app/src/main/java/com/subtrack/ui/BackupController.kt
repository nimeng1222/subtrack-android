package com.subtrack.ui

import android.content.Context
import com.subtrack.AppContainer
import com.subtrack.data.backup.BackupCodec
import com.subtrack.data.backup.BackupFormatException
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.Subscription
import com.subtrack.ui.state.UiMessage
import java.time.LocalDate

/**
 * 备份的导出/导入编排。
 *
 * 单独抽出来是因为这块逻辑（SAF、文件读写、导入摘要、错误兜底）跟界面无关，
 * 留在 MainActivity 里会把导航逻辑冲淡，也没法单独测。
 *
 * 导入的失败边界（v1.3.2 审查结论）：
 * 1. **解析阶段**（纯函数）任何一条失败 → 一个字节都不写；
 * 2. **数据库阶段**（订阅 + 流水）在单个 Room 事务里执行，中途失败整体回滚；
 * 3. **设置阶段**（DataStore）在事务提交之后单独执行，失败不回滚库，
 *    但摘要必须如实报告"数据已导入、设置未能写入"；
 * 4. **调度阶段**（重排提醒）失败只影响提醒，不影响已落库的数据，摘要照常返回。
 *    绝不允许报"新增 0"却已经改了库。
 */
class BackupController(
    private val container: AppContainer,
) {
    /** AC-53：文件名默认含日期。 */
    fun jsonFileName(today: LocalDate): String = BackupCodec.defaultFileName(today)

    fun csvFileName(today: LocalDate): String = "SubTrack-$today.csv"

    /** 导出全量备份：未删除订阅 + 它们的流水 + 设置。
     *
     * 流水按"未删除订阅"过滤，与统计口径一致（删除订阅的历史流水不再计入统计，
     * 也不应随备份流窜到新设备上变成孤立流水）。 */
    suspend fun exportJson(context: Context, uri: android.net.Uri, subs: List<Subscription>, settings: AppSettings) {
        val exportedIds = subs.map { it.id }.toSet()
        val payments = container.repository.allPayments()
            .filter { it.subscriptionId in exportedIds }
            .map {
                com.subtrack.data.backup.PaymentRecord(
                    id = it.id, subscriptionId = it.subscriptionId, paidAt = it.paidAt,
                    amountMinor = it.amountMinor, currency = it.currency, note = it.note,
                )
            }
        val text = BackupCodec.exportJson(subs, payments, settings)
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    /** AC-59：CSV 首字符是 UTF-8 BOM。 */
    suspend fun exportCsv(context: Context, uri: android.net.Uri, subs: List<Subscription>) {
        val text = BackupCodec.exportCsv(subs)
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    /**
     * CSV 导入（P1）。与 JSON 的严格全量语义相反：CSV 逐行容错，
     * 用户只错一行不该让整份文件失败。返回摘要含成功/跳过/失败行数。
     * 已有 id（含软删除）跳过；成功行在单个事务里写入。
     */
    suspend fun importCsv(context: Context, uri: android.net.Uri, today: LocalDate): UiMessage.ImportSummary {
        return runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw BackupFormatException("无法读取文件")
            val existing = container.repository.allIds()
            val result = com.subtrack.data.backup.CsvImporter.parse(text, existing, today)
            if (result.subscriptions.isNotEmpty()) {
                container.repository.runInTransaction {
                    result.subscriptions.forEach { container.repository.upsert(it) }
                }
            }
            runCatching { container.reminderService.reconcile() }
            UiMessage.ImportSummary(
                added = result.subscriptions.size,
                skipped = result.skipped,
                corrected = 0,
                addedPayments = 0,
                csvErrors = result.errors.map { "第 ${it.lineNumber} 行：${it.message}" },
            )
        }.getOrElse { e ->
            UiMessage.ImportSummary(0, 0, 0, 0, error = e.message ?: "CSV 导入失败")
        }
    }

    /**
     * AC-54~58：导入并返回摘要。
     * 解析失败（版本过新 / 文件损坏 / 字段越界）时不写任何数据，直接返回带 error 的摘要。
     */
    suspend fun import(context: Context, uri: android.net.Uri): UiMessage.ImportSummary {
        val parsed = runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw BackupFormatException("无法读取文件")
            // 已存在判断必须包含软删除记录：只看未删除会让旧备份"复活"已删除的订阅
            val existingIds = container.repository.allIds()
            val keys = container.repository.allPayments()
                .map { com.subtrack.data.backup.PaymentKey(it.subscriptionId, it.paidAt) }.toSet()
            BackupCodec.importBackup(text, existingIds, keys)
        }

        return parsed.fold(
            onSuccess = { r ->
                // 流水只能挂在"文件里有、或本地已有"的订阅上，否则恢复出孤立流水
                val knownIds = r.subscriptions.map { it.id }.toSet() + container.repository.allIds()
                val writablePayments = r.payments.filter { it.subscriptionId in knownIds }

                val dbResult = runCatching {
                    container.repository.runInTransaction {
                        r.subscriptions.forEach { container.repository.upsert(it) }
                        container.repository.insertPayments(
                            writablePayments.map {
                                com.subtrack.domain.repository.PaymentRecord(
                                    id = it.id, subscriptionId = it.subscriptionId, paidAt = it.paidAt,
                                    amountMinor = it.amountMinor, currency = it.currency, note = it.note,
                                )
                            },
                        )
                    }
                }
                dbResult.fold(
                    onSuccess = {
                        // 库已提交；设置与调度是独立边界，失败不影响"数据已导入"的事实，
                        // 但必须如实写进摘要，不能让用户以为一切顺利
                        var warning: String? = null
                        r.settings?.let { s ->
                            runCatching { container.settingsStore.update(s.copy(network = container.awaitSettings().network)) }
                                .onFailure { warning = "订阅已导入，但设置未能恢复：${it.message}" }
                        }
                        runCatching { container.reminderService.reconcile() }
                            .onFailure { if (warning == null) warning = "订阅已导入，但提醒重排失败：${it.message}" }
                        UiMessage.ImportSummary(
                            r.added, r.skipped, r.corrected,
                            addedPayments = writablePayments.size,
                            error = warning,
                        )
                    },
                    onFailure = { e ->
                        // 事务回滚 = 数据库一个字节都没动，摘要如报 0
                        UiMessage.ImportSummary(0, 0, 0, 0, error = e.message ?: "写入失败，已回滚")
                    },
                )
            },
            onFailure = { e ->
                UiMessage.ImportSummary(0, 0, 0, 0, error = e.message ?: "导入失败")
            },
        )
    }
}
