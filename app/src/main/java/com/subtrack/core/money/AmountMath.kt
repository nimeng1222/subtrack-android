package com.subtrack.core.money

import java.math.BigInteger
import java.math.RoundingMode
import com.subtrack.domain.model.Limits

/** Long.MAX_VALUE is a presentation overflow marker, never a persisted amount. Exact arithmetic precedes projection. */
object AmountMath {
    val MAX = BigInteger.valueOf(Long.MAX_VALUE)
    fun display(value: BigInteger): Long = if (value >= MAX) Long.MAX_VALUE else { require(value >= Long.MIN_VALUE.toBigInteger()); value.toLong() }
    fun add(a: Long, b: Long): Long = if (a == Long.MAX_VALUE || b == Long.MAX_VALUE) Long.MAX_VALUE
        else display(a.toBigInteger() + b.toBigInteger())
    fun monthly(annual: BigInteger): Long = display((annual + BigInteger.valueOf(6)) / BigInteger.valueOf(12))
}

fun <T> Iterable<T>.sumMoney(value: (T) -> Long): Long = fold(0L) { total, item -> AmountMath.add(total, value(item)) }

object MoneyInput {
    fun minor(raw: String, maximum: Long): Long? {
        // Bound parser work as well as numeric value; never use BigDecimal.toLong truncation.
        val text = raw.trim()
        if (text.length > 80 || !Regex("[0-9]+(?:\\.[0-9]{0,8})?").matches(text)) return null
        val value = try { text.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact() }
            catch (_: ArithmeticException) { null }
        return value?.takeIf { it in 0..maximum }
    }
    fun price(raw: String): Long? = minor(raw, Limits.MAX_PRICE_MINOR)
    fun rate(raw: String): Long? = minor(raw, Limits.MAX_FX_RATE)?.takeIf { it > 0 }
}
