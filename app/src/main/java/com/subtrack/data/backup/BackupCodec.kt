package com.subtrack.data.backup

import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 备份文件自身不合法（损坏 / 字段缺失 / 类型错误 / 版本过新）。
 *
 * 继承 [IllegalArgumentException] 是为了让调用方既能笼统地 `runCatching` 兜底，
 * 又能按类型单独给出"请先升级应用"这类可操作提示。
 */
class BackupFormatException(message: String) : IllegalArgumentException(message)

/**
 * 续费流水（纯领域模型）。
 *
 * 持久层对应 [com.subtrack.data.db.PaymentRecordEntity]，这里刻意不复用 Entity：
 * codec 保持纯 Kotlin，才能在 JVM 单测里直接跑（Room 注解类在单测里没必要引入）。
 */
data class PaymentRecord(
    val id: String,
    val subscriptionId: String,
    val paidAt: LocalDate,
    val amountMinor: Long,
    val currency: String = "CNY",
    val source: String = "USER_CONFIRMED",
    val createdAtUtcMillis: Long = 0L,
    val note: String? = null,
) {
    /** 去重键，与 payment_record 表的唯一索引 `(subscriptionId, paidAtEpochDay)` 对齐。 */
    val key: PaymentKey get() = PaymentKey(subscriptionId, paidAt)
}

/** 流水的唯一键：同一订阅同一天只应有一条流水。 */
data class PaymentKey(val subscriptionId: String, val paidAt: LocalDate)

/**
 * 导入结果摘要（AC-54/55）。
 *
 * [subscriptions] / [payments] 只是"需要写库的那些"，**跳过的条目不在里面** ——
 * 这样调用方拿结果直接 upsert / insert 即可，不需要再做二次过滤。
 */
data class ImportResult(
    /** 实际要写库的订阅：库里还没有 id 的那些（新增）。 */
    val subscriptions: List<Subscription>,
    /** 实际要写库的流水：文件内去重后、且库里还没有的那些。 */
    val payments: List<PaymentRecord>,
    /** 备份里的应用设置；v1 备份或没有设置时为 null。 */
    val settings: AppSettings?,
    /** 新增条数。 */
    val added: Int,
    /** 跳过条数：同 id 已存在（含软删除），本地数据优先，不回写。 */
    val skipped: Int,
    /** 兼容字段，恒为 0：导入不再覆盖本地已有条目（无"修正"语义）。 */
    val corrected: Int,
    /** 新增流水条数。 */
    val addedPayments: Int = 0,
    /** 跳过流水条数：库里已有同 (订阅, 日期) 的流水，或文件内自身重复。 */
    val skippedPayments: Int = 0,
)

/**
 * JSON 全量备份（v2）+ CSV 导出。纯本地、纯函数，不碰数据库也不碰 Android API。
 *
 * 为什么不用 org.json / kotlinx.serialization：
 * - `org.json` 在 Android 单元测试里是 stub，一调用就抛 "not mocked"，codec 就没法被测；
 * - 引新依赖要改 build.gradle.kts，本仓库当前是离线构建，多一个依赖就多一份构建风险。
 * 所以这里手写一个够用的 JSON 读写器（约 150 行），格式完全由我们自己控制。
 *
 * 严格性取舍（AC-57）：整份文件必须全部解析成功才返回，任何一条订阅坏掉都让整次导入失败，
 * 绝不允许"导入一半"。但**允许缺省的字段**（note、可空日期、设置项等）用默认值补齐，不算错误。
 */
object BackupCodec {

    /** 当前支持的最高备份格式版本。v2 = v1 + 续费流水 + 应用设置。 */
    const val FORMAT_VERSION = 2

    private const val APP_NAME = "SubTrack"

    /** UTF-8 BOM。CSV 的第一个字符必须是它，否则 Excel 按 GBK 解码会把中文弄成乱码（AC-59）。 */
    private const val BOM_CHAR = '\uFEFF'

    private const val CSV_HEADER = "name,category,cycle,price_yuan,start_date,next_charge_date,status,note"

    /** AC-53：`SubTrack-20261026.json`。用 BASIC_ISO_DATE 保证补零、不带分隔符。 */
    fun defaultFileName(date: LocalDate): String =
        "SubTrack-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.json"

    // ------------------------------------------------------------------ 导出

    /**
     * AC-52：导出全部订阅 + 全部续费流水 + 全部应用设置。
     *
     * [payments] / [settings] 给默认值是为了保持 v1 的 1 参调用仍然可用（平滑迁移）。
     * settings 缺失时写 `null` 而不是省略字段，导入端才能区分"这份备份没有设置"和"文件被截断"。
     */
    fun exportJson(
        subscriptions: List<Subscription>,
        payments: List<PaymentRecord> = emptyList(),
        settings: AppSettings? = null,
    ): String {
        val root = linkedMapOf<String, JsonValue>(
            "formatVersion" to JsonValue.Num(FORMAT_VERSION.toString()),
            "app" to JsonValue.Str(APP_NAME),
            "exportedAtUtcMillis" to JsonValue.Num(System.currentTimeMillis().toString()),
            "subscriptions" to JsonValue.Arr(subscriptions.map { it.toJson() }),
            "payments" to JsonValue.Arr(payments.map { it.toJson() }),
            "settings" to (settings?.toJson() ?: JsonValue.Null),
        )
        return JsonValue.Obj(root).render()
    }

    /**
     * AC-59：CSV 导出，UTF-8 BOM + 固定列序，金额两位小数。
     * 列序：名称 / 分类 / 金额 / 周期 / 开始日 / 下次到期日 / 状态 / 备注。
     */
    fun exportCsv(subscriptions: List<Subscription>): String {
        val rows = subscriptions.map { sub ->
            listOf(
                sub.name,
                sub.categoryId,
                sub.cycle.name,
                yuanText(sub.priceMinor),
                sub.anchorDate.toString(),
                sub.nextChargeDate?.toString().orEmpty(),
                sub.status.name,
                sub.note.orEmpty(),
            ).joinToString(",") { escapeCsv(it) }
        }
        return BOM_CHAR + (listOf(CSV_HEADER) + rows).joinToString("\n")
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 兼容 v1 的旧入口：只返回订阅列表。**新代码请用 [importBackup]**，这里没有流水和计数。
     */
    fun importJson(text: String): List<Subscription> = importBackup(text).subscriptions

    /**
     * AC-54/55/56/57/58：解析备份并给出导入摘要。
     *
     * 合并语义（与设置页"跳过已存在的条目"一致）：**库里已有的 id 一律跳过，本地数据优先**。
     * [existingIds] 必须包含软删除记录（用 repository.allIds() 取）——否则删除过的订阅
     * 会被旧备份"复活"或把新数据覆盖回旧值。不再有"内容不同就当修正覆盖"的路径；
     * 想恢复到备份状态是明确的独立操作，不归导入管。
     *
     * codec 是纯函数不碰数据库，所以"库里已有什么"由调用方注入：
     * @param existingIds 库内现有订阅 id（含软删除）。命中即跳过，不写入。
     * @param existingPaymentKeys 库内已有流水的去重键；不传则把所有流水都当新流水，
     *   依赖 `insert(onConflict = IGNORE)` 兜住重复（结果里 added 会偏多）。
     *
     * @throws BackupFormatException 版本过新、文件损坏、非 JSON、必填字段缺失，
     *   字段越界（金额/周期），任何一条订阅解析失败。
     */
    fun importBackup(
        text: String,
        existingIds: Set<String> = emptySet(),
        existingPaymentKeys: Set<PaymentKey> = emptySet(),
    ): ImportResult {
        if (text.isBlank()) throw BackupFormatException("备份文件为空")

        // 个别编辑器保存 JSON 会带 BOM，宽容掉；BOM 在 JSON 里本来就不合法
        val root = JsonParser(text.removePrefix("\uFEFF").trim()).parseDocument()
            .asObject("备份根节点")

        val version = root.required("formatVersion", "备份根节点").asLong("formatVersion")
        if (version > FORMAT_VERSION) {
            throw BackupFormatException(
                "该备份来自更新版本的应用，请先升级（备份格式 v$version，当前支持 v$FORMAT_VERSION）",
            )
        }
        if (version < 1) throw BackupFormatException("备份格式版本非法：$version")

        val subscriptionArray = root.required("subscriptions", "备份根节点") as? JsonValue.Arr
            ?: throw BackupFormatException("备份根节点.subscriptions 应为数组")

        // 先全部解析完再往下走：中途抛异常就等于整次导入失败，不会留下半套数据
        val parsed = ArrayList<Subscription>(subscriptionArray.items.size)
        val seenIds = HashSet<String>()
        subscriptionArray.items.forEachIndexed { index, item ->
            val sub = item.toSubscription("subscriptions[$index]")
            if (!seenIds.add(sub.id)) throw BackupFormatException("备份中存在重复的订阅 id：${sub.id}")
            parsed += sub
        }

        // 流水与设置是 v2 新增，v1 备份没有这两个字段，按"没有"处理而不是报错
        val paymentsValue = root.optional("payments")
        val parsedPayments: List<PaymentRecord> = when (paymentsValue) {
            null -> emptyList()
            is JsonValue.Arr -> paymentsValue.items.mapIndexed { index, item ->
                item.toPayment("payments[$index]")
            }
            else -> throw BackupFormatException("备份根节点.payments 应为数组")
        }

        val settingsValue = root.optional("settings")
        val parsedSettings: AppSettings? = when (settingsValue) {
            null -> null
            is JsonValue.Obj -> settingsValue.toAppSettings()
            else -> throw BackupFormatException("备份根节点.settings 应为对象")
        }

        // 同 id 一律跳过（含内容有差异的）：本地数据优先，"修正"不再是覆盖本地改动的路径。
        // corrected 字段保留是为了摘要 UI 兼容，语义上恒为 0。
        val writableSubscriptions = ArrayList<Subscription>(parsed.size)
        var added = 0
        var skipped = 0
        val corrected = 0
        for (sub in parsed) {
            if (sub.id in existingIds) {
                skipped += 1
            } else {
                added += 1
                writableSubscriptions += sub
            }
        }

        val writablePayments = ArrayList<PaymentRecord>(parsedPayments.size)
        val seenKeys = HashSet<PaymentKey>()
        var addedPayments = 0
        var skippedPayments = 0
        for (payment in parsedPayments) {
            val key = payment.key
            val firstInFile = seenKeys.add(key)
            if (firstInFile && key !in existingPaymentKeys) {
                addedPayments += 1
                writablePayments += payment
            } else {
                skippedPayments += 1
            }
        }

        return ImportResult(
            subscriptions = writableSubscriptions,
            payments = writablePayments,
            settings = parsedSettings,
            added = added,
            skipped = skipped,
            corrected = corrected,
            addedPayments = addedPayments,
            skippedPayments = skippedPayments,
        )
    }

    // ------------------------------------------------------------------ CSV 细节

    /** 金额分转元，固定两位小数。 */
    private fun yuanText(minor: Long): String {
        val sign = if (minor < 0) "-" else ""
        val abs = if (minor < 0) -minor else minor
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    /** 逗号、引号、换行都要包引号，内部引号翻倍（RFC 4180）。 */
    private fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

// ======================================================================
// 领域模型 <-> JSON
// ======================================================================
