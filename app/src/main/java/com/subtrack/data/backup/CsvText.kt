package com.subtrack.data.backup

import com.subtrack.data.backup.CsvImporter.RowError

/**
 * CSV 词法层（RFC 4180）：把字符流读成记录，并维护每条记录的真实起始行号。
 *
 * 从 [CsvImporter] 拆出来：这一层只关心"怎么读字符"，CsvImporter 关心"怎么理解业务字段"。
 * 宽容与严格的分界在此很明确——语法尽量宽容（真实 Excel 文件很脏：混合换行、空尾巴、
 * 引号未闭合都常见），业务语义严格（未知枚举必须报错，不许静默降级）。
 *
 * 行号必须逐字符维护：引号内的换行也占真实行，否则带多行备注的文件后面所有行号都会错位。
 */
    /** 一条记录：[cells] 是已解转义的字段，[lineNumber] 是它在文件里**起始**的物理行号。 */
data class Record(val lineNumber: Int, val cells: List<String>)

data class LexResult(val records: List<Record>, val errors: List<RowError>)

    /**
     * RFC 4180 词法：字段可被 `"` 包裹，包裹内可含逗号、换行，`""` 表示一个 `"`。
     *
     * 另有三处刻意的宽容（真实文件里很常见，严格拒绝反而让人无法导入）：
     * - 行尾 `\r\n`、`\n`、单独 `\r` 都算换行；
     * - 空行整行跳过，不算数据行也不报错；
     * - 引号闭合后又冒出字符（`"a"b`）当成字段内容的延续，不报错。
     *   只有"引号一直到文件末尾都没闭合"才报错——这时后面的内容全在引号里，没法继续解析。
     *
     * 行号必须逐字符维护：引号内的换行也占真实行，否则带多行备注的文件后面所有行号都会错位。
     */
fun readRecords(text: String): LexResult {
    val records = ArrayList<Record>()
    val errors = ArrayList<RowError>()
    val length = text.length
    var i = 0
    var line = 1

    while (i < length) {
        val startLine = line
        val cells = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var recordEnded = false

        while (i < length && !recordEnded) {
            val c = text[i]
            if (inQuotes) {
                when {
                    // 引号内的 "" 是一个字面量引号
                    c == '"' && i + 1 < length && text[i + 1] == '"' -> {
                        field.append('"')
                        i += 2
                    }
                    c == '"' -> {
                        inQuotes = false
                        i += 1
                    }
                    else -> {
                        // 引号内换行：是字段内容，但行号照样要涨
                        if (c == '\n') {
                            line += 1
                        } else if (c == '\r' && !(i + 1 < length && text[i + 1] == '\n')) {
                            line += 1
                        }
                        field.append(c)
                        i += 1
                    }
                }
            } else {
                when (c) {
                    '"' -> {
                        inQuotes = true
                        i += 1
                    }
                    ',' -> {
                        cells += field.toString()
                        field.setLength(0)
                        i += 1
                    }
                    '\r', '\n' -> {
                        cells += field.toString()
                        field.setLength(0)
                        i += if (c == '\r' && i + 1 < length && text[i + 1] == '\n') 2 else 1
                        line += 1
                        recordEnded = true
                    }
                    else -> {
                        field.append(c)
                        i += 1
                    }
                }
            }
        }

        if (inQuotes) {
            // 引号没闭合：这条记录连同剩余内容都没法救，报错后停止
            errors += RowError(startLine, "引号未闭合，无法解析该行及其后的内容")
            return LexResult(records, errors)
        }

        if (!recordEnded) {
            // 文件末尾没有换行，最后一条记录靠这里收尾
            cells += field.toString()
        }

        // 全空的记录不算数据行：Excel 另存经常在末尾留一行 `,,,,,`，
        // 把它当"名称为空"报错只会制造一堆噪音
        val isBlankLine = cells.all { it.isBlank() }
        if (!isBlankLine) records += Record(startLine, cells)
    }

    return LexResult(records, errors)
}
