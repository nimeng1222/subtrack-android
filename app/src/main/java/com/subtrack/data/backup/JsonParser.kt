package com.subtrack.data.backup

internal class JsonParser(private val src: String) {

    private var pos = 0
    private var depth = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        if (pos != src.length) fail("JSON 结束后还有多余内容")
        return value
    }

    private fun readValue(): JsonValue {
        if (++depth > 64) fail("JSON 嵌套超过上限")
        try { return readBoundedValue() } finally { depth-- }
    }

    private fun readBoundedValue(): JsonValue {
        if (pos >= src.length) fail("JSON 意外结束")
        return when (val ch = src[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> {
                expect("true")
                JsonValue.Bool(true)
            }
            'f' -> {
                expect("false")
                JsonValue.Bool(false)
            }
            'n' -> {
                expect("null")
                JsonValue.Null
            }
            else -> if (ch == '-' || ch.isDigit()) readNumber() else fail("出现非法字符 '$ch'")
        }
    }

    private fun readObject(): JsonValue.Obj {
        pos += 1 // 跳过 '{'
        val fields = linkedMapOf<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            pos += 1
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            if (fields.containsKey(key)) fail("对象字段重复")
            skipWhitespace()
            if (peek() != ':') fail("对象字段后缺少 ':'")
            pos += 1
            skipWhitespace()
            fields[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos += 1
                '}' -> {
                    pos += 1
                    return JsonValue.Obj(fields)
                }
                else -> fail("对象字段之间缺少 ',' 或未闭合")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        pos += 1 // 跳过 '['
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            pos += 1
            return JsonValue.Arr(items)
        }
        while (true) {
            skipWhitespace()
            items += readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos += 1
                ']' -> {
                    pos += 1
                    return JsonValue.Arr(items)
                }
                else -> fail("数组元素之间缺少 ',' 或未闭合")
            }
        }
    }

    private fun readString(): String {
        if (peek() != '"') fail("应为字符串")
        pos += 1
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) fail("字符串未闭合")
            when (val ch = src[pos]) {
                '"' -> {
                    pos += 1
                    return sb.toString()
                }
                '\\' -> {
                    pos += 1
                    if (pos >= src.length) fail("转义序列不完整")
                    when (val esc = src[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= src.length) fail("\\u 转义不完整")
                            val hex = src.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16) ?: fail("\\u 转义不是合法十六进制：$hex")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("未知转义字符 '\\$esc'")
                    }
                    pos += 1
                }
                else -> {
                    if (ch < ' ') fail("字符串中出现未转义的控制字符")
                    sb.append(ch)
                    pos += 1
                }
            }
        }
    }

    private fun readNumber(): JsonValue.Num {
        val start = pos
        if (peek() == '-') pos += 1
        while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) pos += 1
        val raw = src.substring(start, pos)
        if (raw.toLongOrNull() == null && raw.toDoubleOrNull() == null) fail("非法数字 '$raw'")
        return JsonValue.Num(raw)
    }

    private fun expect(literal: String) {
        if (!src.startsWith(literal, pos)) fail("应为 '$literal'")
        pos += literal.length
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos += 1
    }

    private fun fail(message: String): Nothing =
        throw BackupFormatException("备份文件不是合法的 JSON：$message（位置 $pos）")
}
