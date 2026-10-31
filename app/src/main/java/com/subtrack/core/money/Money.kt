package com.subtrack.core.money

import kotlin.math.abs

object Money {

    /** 整数分 → "¥1,284.33"，全程无浮点。 */
    fun format(minor: Long, symbol: String = "¥"): String {
        if (minor == Long.MAX_VALUE) return "金额超出显示范围（原始数据保留）"
        val negative = minor < 0
        val value = abs(minor)
        val yuan = value / 100
        val cents = (value % 100).toString().padStart(2, '0')
        return buildString {
            if (negative) append('-')
            append(symbol)
            append(groupThousands(yuan))
            append('.')
            append(cents)
        }
    }

    fun formatCompact(minor: Long, symbol: String = "¥"): String {
        if (minor == Long.MAX_VALUE) return format(minor, symbol)
        val value = abs(minor)
        if (value < 100_000_00L) return format(minor, symbol)
        val wan = value / 100_000_0L
        return (if (minor < 0) "-" else "") + symbol + groupThousands(wan) + "万"
    }

    private fun groupThousands(value: Long): String {
        val raw = value.toString()
        if (raw.length <= 3) return raw
        val sb = StringBuilder()
        var count = 0
        for (i in raw.lastIndex downTo 0) {
            sb.append(raw[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(',')
        }
        return sb.reverse().toString()
    }
}
