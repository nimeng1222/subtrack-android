package com.subtrack.data.backup

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 最小 JSON 读写器。只覆盖备份需要的子集，但严格遵守 JSON 语法。
 *
 * 为什么不用 org.json / kotlinx.serialization：
 * - `org.json` 在 Android 单元测试里是 stub，一调用就抛 "not mocked"，codec 就没法被测；
 * - 本仓库是离线构建，kotlinx-serialization 只有 runtime jar、没有编译器插件，加了会构建失败。
 *
 * 数字保留原始文本（[JsonValue.Num]），避免 Double 往返丢精度——priceMinor 与时间戳都是 Long。
 */
// ======================================================================
// JSON 读写器（最小实现，只覆盖备份需要的子集，但严格遵守 JSON 语法）
// ======================================================================

internal const val INDENT = "  "

internal sealed interface JsonValue {
    class Obj(val fields: Map<String, JsonValue>) : JsonValue
    class Arr(val items: List<JsonValue>) : JsonValue
    class Str(val value: String) : JsonValue

    /** 数字保留原始文本，避免 Double 往返丢精度（priceMinor / 时间戳都是 Long）。 */
    class Num(val raw: String) : JsonValue
    class Bool(val value: Boolean) : JsonValue
    object Null : JsonValue
}

internal fun JsonValue.asObject(context: String): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw BackupFormatException("$context 应为 JSON 对象")

internal fun JsonValue.Obj.required(key: String, context: String): JsonValue =
    fields[key] ?: throw BackupFormatException("$context 缺少字段 \"$key\"")

/** 显式 null 与"字段不存在"对可空字段来说语义相同，统一当作缺省。 */
internal fun JsonValue.Obj.optional(key: String): JsonValue? =
    fields[key]?.takeIf { it !is JsonValue.Null }

internal fun JsonValue.asString(context: String): String =
    (this as? JsonValue.Str)?.value ?: throw BackupFormatException("$context 应为字符串")

internal fun JsonValue.asBoolean(context: String): Boolean =
    (this as? JsonValue.Bool)?.value ?: throw BackupFormatException("$context 应为布尔值")

internal fun JsonValue.asLong(context: String): Long {
    val raw = (this as? JsonValue.Num)?.raw ?: throw BackupFormatException("$context 应为数字")
    raw.toLongOrNull()?.let { return it }
    // 容忍 "1500.0" / "1.5e3" 这类等价写法，但小数部分非零就拒绝，不静默丢精度
    return try { raw.toBigDecimal().longValueExact() }
    catch (_: ArithmeticException) { throw BackupFormatException("$context 不是 Long 范围内的整数：$raw") }
    catch (_: NumberFormatException) { throw BackupFormatException("$context 不是整数：$raw") }
}

internal fun JsonValue.asInt(context: String): Int {
    val value = asLong(context)
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        throw BackupFormatException("$context 超出整数范围：$value")
    }
    return value.toInt()
}

/** Room 里日期以 epochDay(Int) 存，超出范围的日期写库会被截断，这里提前拒绝。 */
internal fun JsonValue.asDate(context: String): LocalDate {
    val text = asString(context)
    val date = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        throw BackupFormatException("$context 不是合法日期：$text")
    }
    val epochDay = date.toEpochDay()
    if (epochDay < Int.MIN_VALUE || epochDay > Int.MAX_VALUE) {
        throw BackupFormatException("$context 超出可存储的日期范围：$text")
    }
    return date
}

internal fun JsonValue.render(): String = StringBuilder().also { appendJson(it, this, 0) }.toString()

internal fun appendJson(sb: StringBuilder, value: JsonValue, depth: Int) {
    when (value) {
        is JsonValue.Obj -> {
            if (value.fields.isEmpty()) {
                sb.append("{}")
                return
            }
            sb.append("{\n")
            var first = true
            for ((key, child) in value.fields) {
                if (!first) sb.append(",\n")
                first = false
                indent(sb, depth + 1)
                sb.append(quote(key)).append(": ")
                appendJson(sb, child, depth + 1)
            }
            sb.append('\n')
            indent(sb, depth)
            sb.append('}')
        }
        is JsonValue.Arr -> {
            if (value.items.isEmpty()) {
                sb.append("[]")
                return
            }
            sb.append("[\n")
            value.items.forEachIndexed { index, child ->
                if (index > 0) sb.append(",\n")
                indent(sb, depth + 1)
                appendJson(sb, child, depth + 1)
            }
            sb.append('\n')
            indent(sb, depth)
            sb.append(']')
        }
        is JsonValue.Str -> sb.append(quote(value.value))
        is JsonValue.Num -> sb.append(value.raw)
        is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
        JsonValue.Null -> sb.append("null")
    }
}

internal fun indent(sb: StringBuilder, depth: Int) {
    repeat(depth) { sb.append(INDENT) }
}

internal fun quote(raw: String): String = buildString(raw.length + 2) {
    append('"')
    for (ch in raw) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (ch < ' ') {
                append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

/** 递归下降解析器：任何语法问题都抛 [BackupFormatException]，不做"尽力恢复"。 */
