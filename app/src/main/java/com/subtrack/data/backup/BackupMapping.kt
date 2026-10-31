package com.subtrack.data.backup

import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.model.ThemeMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.LocalTime

/**
 * 领域模型 ↔ JSON 的映射层。与 [BackupCodec] 分开是因为这一层全是对字段的机械搬运，
 * 和"版本、校验、计数"这些协议逻辑混在一起会两边都难读。
 *
 * 严格性：未知枚举值一律抛 [BackupFormatException]，不做静默降级——
 * 把坏数据悄悄写进库比崩溃更危险（这是 v1 的一个真实缺陷）。
 */
internal fun Subscription.toJson(): JsonValue = JsonValue.Obj(
    linkedMapOf(
        "id" to JsonValue.Str(id),
        "name" to JsonValue.Str(name),
        "categoryId" to JsonValue.Str(categoryId),
        "paymentMethodId" to (paymentMethodId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
        "cycle" to JsonValue.Str(cycle.name),
        "customCycleDays" to (customCycleDays?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null),
        "anchorDate" to JsonValue.Str(anchorDate.toString()),
        "nextChargeDate" to (nextChargeDate?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null),
        "priceMinor" to JsonValue.Num(priceMinor.toString()),
        "currency" to JsonValue.Str(currency),
        "status" to JsonValue.Str(status.name),
        "autoRenew" to JsonValue.Bool(autoRenew),
        "trialEndDate" to (trialEndDate?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null),
        "canceledAt" to (canceledAt?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null),
        "reminderOffsets" to JsonValue.Arr(reminderOffsets.map { JsonValue.Num(it.toString()) }),
        "mutedUntil" to (mutedUntil?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null),
        "note" to (note?.let { JsonValue.Str(it) } ?: JsonValue.Null),
        "deletedAtUtcMillis" to (deletedAtUtcMillis?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null),
    ),
)

internal fun PaymentRecord.toJson(): JsonValue = JsonValue.Obj(
    linkedMapOf(
        "id" to JsonValue.Str(id),
        "subscriptionId" to JsonValue.Str(subscriptionId),
        "paidAt" to JsonValue.Str(paidAt.toString()),
        "amountMinor" to JsonValue.Num(amountMinor.toString()),
        "currency" to JsonValue.Str(currency),
        "source" to JsonValue.Str(source),
        "createdAtUtcMillis" to JsonValue.Num(createdAtUtcMillis.toString()),
        "note" to (note?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    ),
)

/**
 * 设置的序列化。
 *
 * **加 AppSettings 字段时必须同步改这里的两处（写 + 读）**，
 * 否则备份会静默丢掉新设置——用户导出再导入，主题、预算、汇率全没了，
 * 而且不报错。这是和 SettingsStore 一样的坑，两处都要守。
 */
internal fun AppSettings.toJson(): JsonValue = JsonValue.Obj(
    linkedMapOf(
        "defaultReminderOffsets" to JsonValue.Arr(defaultReminderOffsets.map { JsonValue.Num(it.toString()) }),
        "reminderTime" to JsonValue.Str(reminderTime.toString()),
        "notificationsBannerDismissed" to JsonValue.Bool(notificationsBannerDismissed),
        "themeMode" to JsonValue.Str(themeMode.name),
        "appLockEnabled" to JsonValue.Bool(appLockEnabled),
        "monthlyBudgetMinor" to (monthlyBudgetMinor?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null),
        "priceWatchEnabled" to JsonValue.Bool(priceWatchEnabled),
        "healthCheckEnabled" to JsonValue.Bool(healthCheckEnabled),
        "multiCurrencyEnabled" to JsonValue.Bool(multiCurrencyEnabled),
        "fxRates" to JsonValue.Obj(
            fxRates.entries.sortedBy { it.key }
                .associate { it.key to JsonValue.Num(it.value.toString()) },
        ),
    ),
)

/**
 * 必填字段：id / name / categoryId / cycle / anchorDate / priceMinor —— 缺一个就说明文件不是我们写的。
 * 其余字段都有领域默认值，缺省时按默认值补齐（AC-57 允许"确实可缺省"的字段缺省）。
 * 枚举与日期一律严格校验，不认识的值直接拒绝，避免把坏数据悄悄变成 MONTHLY / ACTIVE。
 *
 * 业务边界校验（与编辑页、CycleEngine、Annualizer 共用 [Limits]，不另立口径）：
 * customCycleDays 必须在引擎接受的 1..3650；金额必须能安全参与年化/汇总运算。
 * 放进来的数据都要被 CycleEngine（require 1..3650）和 Annualizer（multiplyExact）消费，
 * 校验放宽一格，导入后每次打开 App 都可能崩溃。
 */
internal fun JsonValue.toSubscription(context: String): Subscription {
    val obj = asObject(context)
    val name = obj.required("name", context).asString("$context.name")
    if (name.isBlank()) throw BackupFormatException("$context.name 不能为空")

    val cycleName = obj.required("cycle", context).asString("$context.cycle")
    val cycle = cycleOf(cycleName, "$context.cycle")
    val customCycleDays = obj.optional("customCycleDays")?.asInt("$context.customCycleDays")
        ?.takeIf { com.subtrack.domain.model.Limits.isValidCustomCycleDays(it) }
    if (cycle == BillingCycleType.CUSTOM_DAYS && customCycleDays == null) {
        throw BackupFormatException(
            "$context 的周期为 CUSTOM_DAYS，但 customCycleDays 缺失或不在 " +
                "${com.subtrack.domain.model.Limits.CUSTOM_CYCLE_DAYS_MIN}.." +
                "${com.subtrack.domain.model.Limits.CUSTOM_CYCLE_DAYS_MAX} 范围内",
        )
    }

    val id = obj.required("id", context).asString("$context.id")
    if (id.isBlank()) throw BackupFormatException("$context.id 不能为空")

    val priceMinor = obj.required("priceMinor", context).asLong("$context.priceMinor")
    if (!com.subtrack.domain.model.Limits.isValidPriceMinor(priceMinor)) {
        throw BackupFormatException(
            "$context.priceMinor 超出可安全计算的金额范围（1 分 ~ " +
                "${com.subtrack.domain.model.Limits.MAX_PRICE_MINOR / 100_000_000L} 亿元）",
        )
    }

    return Subscription(
        id = id,
        name = name,
        categoryId = obj.required("categoryId", context).asString("$context.categoryId"),
        paymentMethodId = obj.optional("paymentMethodId")?.asString("$context.paymentMethodId"),
        cycle = cycle,
        customCycleDays = customCycleDays,
        anchorDate = obj.required("anchorDate", context).asDate("$context.anchorDate"),
        nextChargeDate = obj.optional("nextChargeDate")?.asDate("$context.nextChargeDate"),
        priceMinor = priceMinor,
        currency = obj.optional("currency")?.asString("$context.currency") ?: "CNY",
        status = obj.optional("status")?.let { statusOf(it.asString("$context.status"), "$context.status") }
            ?: SubscriptionStatus.ACTIVE,
        autoRenew = obj.optional("autoRenew")?.asBoolean("$context.autoRenew") ?: true,
        trialEndDate = obj.optional("trialEndDate")?.asDate("$context.trialEndDate"),
        canceledAt = obj.optional("canceledAt")?.asDate("$context.canceledAt"),
        reminderOffsets = obj.optional("reminderOffsets")?.toOffsets("$context.reminderOffsets") ?: listOf(3, 0),
        mutedUntil = obj.optional("mutedUntil")?.asDate("$context.mutedUntil"),
        note = obj.optional("note")?.asString("$context.note"),
        deletedAtUtcMillis = obj.optional("deletedAtUtcMillis")?.asLong("$context.deletedAtUtcMillis"),
    )
}

internal fun JsonValue.toPayment(context: String): PaymentRecord {
    val obj = asObject(context)
    val id = obj.required("id", context).asString("$context.id")
    if (id.isBlank()) throw BackupFormatException("$context.id 不能为空")
    val subscriptionId = obj.required("subscriptionId", context).asString("$context.subscriptionId")
    if (subscriptionId.isBlank()) throw BackupFormatException("$context.subscriptionId 不能为空")
    val amountMinor = obj.required("amountMinor", context).asLong("$context.amountMinor")
    if (!com.subtrack.domain.model.Limits.isValidPaymentMinor(amountMinor)) {
        throw BackupFormatException("$context.amountMinor 超出可安全计算的金额范围")
    }

    return PaymentRecord(
        id = id,
        subscriptionId = subscriptionId,
        paidAt = obj.required("paidAt", context).asDate("$context.paidAt"),
        amountMinor = amountMinor,
        currency = obj.optional("currency")?.asString("$context.currency") ?: "CNY",
        source = obj.optional("source")?.asString("$context.source") ?: "USER_CONFIRMED",
        createdAtUtcMillis = obj.optional("createdAtUtcMillis")?.asLong("$context.createdAtUtcMillis") ?: 0L,
        note = obj.optional("note")?.asString("$context.note"),
    )
}

internal fun JsonValue.Obj.toAppSettings(): AppSettings = AppSettings(
    defaultReminderOffsets = optional("defaultReminderOffsets")
        ?.toOffsets("settings.defaultReminderOffsets")
        ?: AppSettings().defaultReminderOffsets,
    reminderTime = optional("reminderTime")?.let {
        val text = it.asString("settings.reminderTime")
        try {
            LocalTime.parse(text)
        } catch (e: DateTimeParseException) {
            throw BackupFormatException("settings.reminderTime 不是合法时间：$text")
        }
    } ?: LocalTime.of(9, 0),
    notificationsBannerDismissed = optional("notificationsBannerDismissed")
        ?.asBoolean("settings.notificationsBannerDismissed")
        ?: false,
    // L1/L2 新增字段：老备份里没有，一律按默认值补齐（AC-57 允许可缺省字段缺省）
    themeMode = optional("themeMode")
        ?.let { v ->
            val raw = v.asString("settings.themeMode")
            ThemeMode.entries.firstOrNull { it.name == raw }
                ?: throw BackupFormatException("settings.themeMode 不是合法取值：$raw")
        }
        ?: ThemeMode.SYSTEM,
    appLockEnabled = optional("appLockEnabled")
        ?.asBoolean("settings.appLockEnabled") ?: false,
    monthlyBudgetMinor = optional("monthlyBudgetMinor")
        ?.takeIf { it !is JsonValue.Null }
        ?.asLong("settings.monthlyBudgetMinor")
        ?.takeIf { it > 0L },
    priceWatchEnabled = optional("priceWatchEnabled")
        ?.asBoolean("settings.priceWatchEnabled") ?: false,
    healthCheckEnabled = optional("healthCheckEnabled")
        ?.asBoolean("settings.healthCheckEnabled") ?: false,
    multiCurrencyEnabled = optional("multiCurrencyEnabled")
        ?.asBoolean("settings.multiCurrencyEnabled") ?: false,
    fxRates = optional("fxRates")
        ?.takeIf { it !is JsonValue.Null }
        ?.asObject("settings.fxRates")
        ?.fields
        ?.mapNotNull { (code, value) ->
            val minor = value.asLong("settings.fxRates.$code")
            // 超出运算安全上限的汇率丢弃而不是崩溃：折算时 minor×rate 不能溢出
            if (minor > 0L && minor <= com.subtrack.domain.model.Limits.MAX_FX_RATE) code to minor else null
        }
        ?.toMap()
        ?: emptyMap(),
)

/** 提醒偏移：v2 是数组，v1 是 "3,0" 这种 CSV 字符串，两种都认。 */
internal fun JsonValue.toOffsets(context: String): List<Int> = when (this) {
    is JsonValue.Arr -> items.map { it.asInt(context) }
    is JsonValue.Str -> value.split(',').mapNotNull { it.trim().toIntOrNull() }
    else -> throw BackupFormatException("$context 应为数组")
}

internal fun cycleOf(raw: String, context: String): BillingCycleType =
    BillingCycleType.entries.firstOrNull { it.name == raw }
        ?: throw BackupFormatException("$context 不是已知的计费周期：$raw")

internal fun statusOf(raw: String, context: String): SubscriptionStatus =
    SubscriptionStatus.entries.firstOrNull { it.name == raw }
        ?: throw BackupFormatException("$context 不是已知的订阅状态：$raw")
