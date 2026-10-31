package com.subtrack.data.backup

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * CSV 导入（与 [BackupCodec.exportCsv] 严格互逆）。纯函数、不碰数据库、不碰 Android API，
 * 所以能在 JVM 单测里直接跑。
 *
 * 与 JSON 备份的取舍**故意不同**：JSON 是全量快照，一条坏掉就整份失败（AC-57）；
 * CSV 是给人看、给 Excel 改的文件，用户往往只错一行，整份拒绝等于让人没法用。
 * 所以这里逐行容错：坏的跳过并进 [Result.errors]，好的照常导入。
 *
 * 三个关键设计：
 * 1. 列按**列名**匹配而不是位置——用户在 Excel 里拖一列、加一列都不该让导入错乱；
 * 2. 枚举严格：未知周期/状态报行错误，绝不静默降级成 MONTHLY / ACTIVE（沿用 [BackupMapping] 的原则）；
 * 3. 金额走 [BigDecimal]，全程不出现 Double：0.1 + 0.2 那套浮点误差在钱上是不能接受的。
 */
object CsvImporter {

    /** 单行失败的原因。[lineNumber] 是**文件里的真实物理行号**（表头算第 1 行，从 1 开始）。 */
    data class RowError(val lineNumber: Int, val message: String)

    /**
     * 导入结果。
     *
     * [subscriptions] 只是"需要写库的那些"：被 [parse] 按 existingIds 跳过的不在里面，
     * 调用方拿结果直接 upsert 即可，不用二次过滤。
     */
    data class Result(
        /** 解析成功、且库里还没有的订阅。 */
        val subscriptions: List<Subscription>,
        /** 失败的数据行（按行号升序）。 */
        val errors: List<RowError>,
        /** 数据行总数，不含表头行；包含失败的、被跳过的。 */
        val totalRows: Int,
    ) {
        /** 条数 = 数据行 - 成功 - 失败，即因 id 已存在而跳过的行数。 */
        val skipped: Int get() = (totalRows - subscriptions.size - errors.size).coerceAtLeast(0)
    }

    private const val BOM_CHAR = '\uFEFF'

    /** 列的身份。顺序无意义，匹配靠 [HEADER_ALIASES]。 */
    private enum class Field { NAME, CATEGORY, CYCLE, CUSTOM_DAYS, PRICE, START, NEXT, STATUS, NOTE }

    /**
     * 表头别名表。key 都已经过 [normalizeHeader]：小写、去空格、去下划线/短横线。
     * 中英文都认，是因为导出文件是我们自己写的（英文），但用户可能用中文 Excel 模板重新做过表头。
     */
    private val HEADER_ALIASES: Map<Field, Set<String>> = mapOf(
        Field.NAME to setOf("name", "名称", "订阅名称", "名字"),
        Field.CATEGORY to setOf("category", "categoryid", "分类", "类别"),
        Field.CYCLE to setOf("cycle", "billingcycle", "计费周期", "周期"),
        // 导出文件没有这一列，所以它是可选的：CUSTOM_DAYS 靠它拿天数
        Field.CUSTOM_DAYS to setOf("customcycledays", "自定义天数", "周期天数"),
        Field.PRICE to setOf("priceyuan", "price", "amount", "yuan", "金额", "价格", "费用"),
        Field.START to setOf("startdate", "start", "anchor", "anchordate", "开始日", "开始日期", "开始时间"),
        Field.NEXT to setOf(
            "nextchargedate", "nextcharge",
            "下次到期日", "下次扣费日", "下次扣费日期", "下次续费日",
        ),
        Field.STATUS to setOf("status", "状态"),
        Field.NOTE to setOf("note", "备注", "说明"),
    )

    /** 中文周期名 → 枚举。导出的 CSV 写的是 `MONTHLY`，但用户手改的中文模板写"月付"。 */

    /** AC-53 同一命名规则：`SubTrack-20261026.csv`，个位数月份补零保证文件名排序稳定。 */
    fun defaultFileName(date: LocalDate): String =
        "SubTrack-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.csv"

    /**
     * 解析 CSV 文本。
     *
     * @param text 可能带 UTF-8 BOM（我们自己导出的就一定带），也可能不带。
     * @param existingIds 库里已有的订阅 id。CSV 的 id 是**按行号 + 名称**确定性生成的，
     *   所以在里面就说明"这条导过了"，直接跳过——同一份文件导两次不会产生重复（幂等）。
     * @param today `start_date` 缺失时的兜底日期。刻意做成参数而不是内部 `LocalDate.now()`：
     *   否则单测没法稳定断言，跨零点跑也会抖。
     */
    fun parse(
        text: String,
        existingIds: Set<String> = emptySet(),
        today: LocalDate = LocalDate.now(),
    ): Result {
        // 1. BOM：有无都接受。用 removePrefix 而不是 replace，避免把正文里合法的 U+FEFF 也吃掉
        val body = text.removePrefix(BOM_CHAR.toString())

        // 2. 词法：一次扫完，顺带拿到每条记录的真实起始行号
        val lexed = readRecords(body)
        val records = lexed.records
        if (records.isEmpty()) return Result(emptyList(), lexed.errors, 0)

        // 3. 表头：第一个非空记录。列按名字认，不认位置
        val header = records.first()
        val columns = mapColumns(header.cells)
        val dataRecords = records.drop(1)
        if (Field.NAME !in columns) {
            // 没有名称列就无从下手：宁可明确报错，也不要猜列位置把整份数据导歪
            val error = RowError(header.lineNumber, "表头缺少 name/名称 列，无法导入")
            return Result(emptyList(), sortErrors(lexed.errors + error), dataRecords.size)
        }

        val subscriptions = ArrayList<Subscription>(dataRecords.size)
        val errors = ArrayList<RowError>(lexed.errors)

        for (record in dataRecords) {
            try {
                val sub = parseRow(record, columns, today)
                // 幂等：同 id 已存在就跳过。注意这里**不是**错误，所以不进 errors
                if (sub.id !in existingIds) subscriptions += sub
            } catch (e: RowFailure) {
                errors += RowError(record.lineNumber, e.detail)
            }
        }

        return Result(
            subscriptions = subscriptions,
            errors = sortErrors(errors),
            totalRows = dataRecords.size,
        )
    }

    // ------------------------------------------------------------------ 单行解析

    /** 单行解析失败。用异常而不是 Either：一行的校验有七八个分支，用异常才能让主流程线性可读。 */
    private class RowFailure(message: String) : Exception(message) {
        /** 收窄成非空：所有构造点都给了消息，这里省掉调用侧的 `?: ""` 噪音。 */
        val detail: String get() = message.orEmpty()
    }

    private fun parseRow(record: Record, columns: Map<Field, Int>, today: LocalDate): Subscription {
        val name = record.cell(columns, Field.NAME).orEmpty().trim()
        if (name.isEmpty()) fail("名称不能为空")

        val cycleText = record.cell(columns, Field.CYCLE).orEmpty().trim()
        if (cycleText.isEmpty()) fail("计费周期不能为空")
        val cycle = cycleOf(cycleText) ?: fail("未知的计费周期：$cycleText")

        // 只有 CUSTOM_DAYS 才去看天数：别的周期里这列就算脏（用户自己加的列）也不该拖垮整行
        val customCycleDays = if (cycle == BillingCycleType.CUSTOM_DAYS) {
            val raw = record.cell(columns, Field.CUSTOM_DAYS)?.trim().orEmpty()
            if (raw.isEmpty()) {
                // 导出文件里没有天数这一列，这时 CUSTOM_DAYS 只能兜底。
                // 用 30 天而不是报错：录入页"自定义天数"的默认值就是 30，等于沿用 App 自己的约定；
                // 而报错会让"导出→再导入"这条最基本的往返在自定义周期上直接断掉。
                DEFAULT_CUSTOM_DAYS
            } else {
                val days = raw.toIntOrNull() ?: fail("自定义天数不是整数：$raw")
                if (days !in 1..3650) fail("自定义天数超出 1..3650：$days")
                days
            }
        } else {
            null
        }

        val priceText = record.cell(columns, Field.PRICE).orEmpty()
        val priceMinor = parseYuanToMinor(priceText) ?: fail("金额非法：${priceText.trim()}")
        // 与 JSON 导入同一个边界：金额要能安全参与年化/汇总，超限的行报行错误
        if (!com.subtrack.domain.model.Limits.isValidPriceMinor(priceMinor)) {
            fail("金额超出可安全计算的范围：${priceText.trim()}")
        }

        // start_date 缺失/为空时用 today 兜底（要求 8）。这是"确实可缺省"的字段，不算错误
        val startRaw = record.cell(columns, Field.START).orEmpty().trim()
        val anchorDate = if (startRaw.isEmpty()) {
            today
        } else {
            parseDateOrNull(startRaw) ?: fail("开始日期非法：$startRaw")
        }

        val nextRaw = record.cell(columns, Field.NEXT).orEmpty().trim()
        val nextChargeDate = if (nextRaw.isEmpty()) {
            null
        } else {
            parseDateOrNull(nextRaw) ?: fail("下次到期日非法：$nextRaw")
        }

        val statusRaw = record.cell(columns, Field.STATUS).orEmpty().trim()
        val status = if (statusRaw.isEmpty()) {
            SubscriptionStatus.ACTIVE
        } else {
            statusOf(statusRaw) ?: fail("未知的订阅状态：$statusRaw")
        }

        val category = record.cell(columns, Field.CATEGORY).orEmpty().trim()
            .ifEmpty { DEFAULT_CATEGORY_ID }
        val note = record.cell(columns, Field.NOTE)?.trim()?.takeIf { it.isNotEmpty() }

        return Subscription(
            id = stableId(record.lineNumber, name),
            name = name,
            categoryId = category,
            cycle = cycle,
            customCycleDays = customCycleDays,
            anchorDate = anchorDate,
            nextChargeDate = nextChargeDate,
            priceMinor = priceMinor,
            status = status,
            note = note,
        )
    }

    /** 分类列缺失/为空时的落点，与 [com.subtrack.ui.Categories] 的"其他"保持一致。 */
    private const val DEFAULT_CATEGORY_ID = "other"

    /** CUSTOM_DAYS 没给天数时的兜底，与录入页"自定义天数"的默认值一致。 */
    private const val DEFAULT_CUSTOM_DAYS = 30

    private fun fail(message: String): Nothing = throw RowFailure(message)

    // ------------------------------------------------------------------ id

    /**
     * 稳定 id：`csv-<行号>-<名称的 FNV-1a 32 位哈希>`。
     *
     * 为什么不用 UUID —— 幂等要求同一个文件解析两次得到同样的 id，随机 id 永远做不到；
     * 为什么用 FNV-1a 而不是 String.hashCode —— hashCode 虽然也稳定，但它是"实现细节"，
     * 换个语言重写解析器就全变了；FNV-1a 是自描述算法，拿 UTF-8 字节算，中文名也不受平台编码影响。
     *
     * 代价：在文件中间插一行会让后面所有 id 平移 ⇒ 那部分会被当成新记录。
     * 可接受：CSV 导入本身就是"补数据"场景，位置变了的行本来就是新内容。
     */
    private fun stableId(lineNumber: Int, name: String): String =
        "csv-$lineNumber-${fnv1a32(name).toString(16).padStart(8, '0')}"

    private fun fnv1a32(text: String): Long {
        var hash = 0x811C9DC5L
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash = (hash * 0x01000193L) and 0xFFFF_FFFFL
        }
        return hash
    }

    // ------------------------------------------------------------------ 表头

    /** 表头归一化：小写、去空格、去 `_`/`-`，这样 `Price_Yuan`、`price yuan` 都能认。 */
    private fun normalizeHeader(raw: String): String = raw.trim()
        .removePrefix(BOM_CHAR.toString())
        .lowercase()
        .filterNot { it == ' ' || it == '_' || it == '-' || it == '\u00A0' }

    /** 列名 → 下标。同一列名出现多次时以第一列为准（后面的多半是用户多拖的副本）。 */
    private fun mapColumns(cells: List<String>): Map<Field, Int> {
        val result = LinkedHashMap<Field, Int>()
        cells.forEachIndexed { index, raw ->
            val key = normalizeHeader(raw)
            if (key.isEmpty()) return@forEachIndexed
            val field = HEADER_ALIASES.entries.firstOrNull { key in it.value }?.key ?: return@forEachIndexed
            result.putIfAbsent(field, index)
        }
        return result
    }

    /** 按列名取值；该列不存在（或这行列数不够）时返回 null，由调用方按"字段缺省"处理。 */
    private fun Record.cell(columns: Map<Field, Int>, field: Field): String? {
        val index = columns[field] ?: return null
        return cells.getOrNull(index)
    }

    private fun sortErrors(errors: List<RowError>): List<RowError> = errors.sortedBy { it.lineNumber }
}
