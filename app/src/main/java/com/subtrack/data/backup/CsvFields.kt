package com.subtrack.data.backup

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * CSV 的字段级解析：周期、状态、金额、日期。
 *
 * 全部返回 null 表示"无法解析"，由上层决定是报行错误还是用缺省值——
 * 这一层不抛异常，是为了让 CsvImporter 能精确控制"哪些字段可以不填"。
 *
 * 金额一定走 [BigDecimal]：浮点在钱上迟早出事故（`0.1` 用 Double 会变成 0.1000000000000000055）。
 * 小数超过两位直接判非法而不是四舍五入——把 15.555 悄悄变成 15.56 是在替用户改账。
 */
/**
 * 周期：先按枚举名（大小写不敏感），再按中文别名。
 * 认不出来就返回 null（由调用方转成行错误），**绝不** fallback 到 MONTHLY。
 */
internal val CYCLE_ALIASES: Map<String, BillingCycleType> = mapOf(
    "周付" to BillingCycleType.WEEKLY,
    "每周" to BillingCycleType.WEEKLY,
    "按周" to BillingCycleType.WEEKLY,
    "双周付" to BillingCycleType.BIWEEKLY,
    "双周" to BillingCycleType.BIWEEKLY,
    "每两周" to BillingCycleType.BIWEEKLY,
    "四周付" to BillingCycleType.FOUR_WEEKLY,
    "四周" to BillingCycleType.FOUR_WEEKLY,
    "每四周" to BillingCycleType.FOUR_WEEKLY,
    "月付" to BillingCycleType.MONTHLY,
    "每月" to BillingCycleType.MONTHLY,
    "按月" to BillingCycleType.MONTHLY,
    "季付" to BillingCycleType.QUARTERLY,
    "每季" to BillingCycleType.QUARTERLY,
    "按季" to BillingCycleType.QUARTERLY,
    "半年付" to BillingCycleType.SEMIANNUAL,
    "半年" to BillingCycleType.SEMIANNUAL,
    "每半年" to BillingCycleType.SEMIANNUAL,
    "年付" to BillingCycleType.YEARLY,
    "每年" to BillingCycleType.YEARLY,
    "按年" to BillingCycleType.YEARLY,
    "自定义" to BillingCycleType.CUSTOM_DAYS,
    "自定义天数" to BillingCycleType.CUSTOM_DAYS,
    "一次性" to BillingCycleType.ONE_TIME,
    "单次" to BillingCycleType.ONE_TIME,
    "买断" to BillingCycleType.ONE_TIME,
)

/** 中文状态名 → 枚举。 */
internal val STATUS_ALIASES: Map<String, SubscriptionStatus> = mapOf(
    "生效" to SubscriptionStatus.ACTIVE,
    "活跃" to SubscriptionStatus.ACTIVE,
    "正常" to SubscriptionStatus.ACTIVE,
    "有效" to SubscriptionStatus.ACTIVE,
    "使用中" to SubscriptionStatus.ACTIVE,
    "试用" to SubscriptionStatus.TRIAL,
    "试用中" to SubscriptionStatus.TRIAL,
    "暂停" to SubscriptionStatus.PAUSED,
    "已暂停" to SubscriptionStatus.PAUSED,
    "暂停中" to SubscriptionStatus.PAUSED,
    "已取消" to SubscriptionStatus.CANCELED,
    "取消" to SubscriptionStatus.CANCELED,
    "已过期" to SubscriptionStatus.EXPIRED,
    "过期" to SubscriptionStatus.EXPIRED,
)

/** 金额里允许出现、需要先剥掉的符号与单位（空格、千分位逗号也算）。 */
internal val MONEY_NOISE = "¥￥\$元 \u00A0\t,'".toCharArray()

/** `2026-10-26` / `2026/1/5` / `2026.10.26` 都认。 */
internal val DATE_PATTERN = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$""")

/** `20261026` 也认（Excel 有时会把日期压成数字）。 */
internal val COMPACT_DATE_PATTERN = Regex("""^(\d{8})$""")

internal fun cycleOf(raw: String): BillingCycleType? {
    val text = raw.trim()
    CYCLE_ALIASES[text]?.let { return it }
    return BillingCycleType.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
}

/** 状态：同 [cycleOf] 的严格策略。 */
internal fun statusOf(raw: String): SubscriptionStatus? {
    val text = raw.trim()
    STATUS_ALIASES[text]?.let { return it }
    return SubscriptionStatus.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
}

/**
 * 元 → 分。全程 [BigDecimal]：`"0.1"` 这种用 Double 解析会变成 0.1000000000000000055…
 *
 * 支持 `15` / `15.5` / `15.50` / `¥15.00` / `￥1,299.00` / `15元` / `15.555`（拒绝）。
 * 小数超过 2 位直接判非法，而不是四舍五入：把 15.555 悄悄变成 15.56 是在替用户改账。
 *
 * @return 负数、超过 2 位小数、含非法字符、溢出 [Long] 时返回 null。
 */
internal fun parseYuanToMinor(raw: String): Long? {
    val cleaned = buildString {
        for (c in raw) if (c !in MONEY_NOISE) append(c)
        // 去掉中文单位以外的残留单位（CNY / RMB / 人民币），大小写都认
    }.replace("CNY", "", ignoreCase = true)
        .replace("RMB", "", ignoreCase = true)
        .replace("人民币", "")
    if (cleaned.isEmpty()) return null
    // 只允许数字、正负号、小数点：BigDecimal 会放过 "1E+3" 这类科学计数法，
    // 金额虽然也能表示，但用户在金额栏写科学计数法基本等于写错，先挡掉
    if (!cleaned.matches(Regex("""^-?\d+(\.\d+)?$"""))) return null

    val yuan = try {
        BigDecimal(cleaned)
    } catch (e: NumberFormatException) {
        return null
    }
    if (yuan.signum() < 0) return null

    // movePointRight(2) 是精确的十进制移位，不产生二进制误差
    val cents = yuan.movePointRight(2)
    if (cents.stripTrailingZeros().scale() > 0) return null // 超过两位小数
    return try {
        cents.setScale(0).longValueExact()
    } catch (e: ArithmeticException) {
        null // 溢出 Long（金额大到这种程度属于文件被改坏了）
    }
}

/**
 * 日期：`2026-10-26` / `2026/1/5` / `2026.10.26` / `20261026`，
 * 也容忍后面跟着时间（`2026-10-26 00:00:00`、`2026-10-26T00:00`）。
 * 非法（13 月、2 月 30 日）一律返回 null，交给调用方报行错误。
 */
internal fun parseDateOrNull(raw: String): LocalDate? {
    val text = raw.trim().substringBefore(' ').substringBefore('T').trim()
    if (text.isEmpty()) return null

    DATE_PATTERN.matchEntire(text)?.let { m ->
        val (y, mo, d) = m.destructured
        return safeDate(y.toInt(), mo.toInt(), d.toInt())
    }
    COMPACT_DATE_PATTERN.matchEntire(text)?.let { m ->
        val digits = m.groupValues[1]
        return safeDate(
            digits.substring(0, 4).toInt(),
            digits.substring(4, 6).toInt(),
            digits.substring(6, 8).toInt(),
        )
    }
    return null
}

private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
    LocalDate.of(year, month, day)
} catch (e: java.time.DateTimeException) {
    null
}
