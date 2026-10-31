package com.subtrack.domain.stats

import com.subtrack.core.money.AmountMath
import com.subtrack.domain.model.Subscription
import java.math.BigInteger

/** Values are nano-CNY-minor units; aggregate first, then round only the displayed total. */
object FxAmounts {
    val SCALE: BigInteger = FxPrecision.SCALE.toBigInteger()
    fun scaled(minor: Long, currency: String, rates: Map<String, Long>?): BigInteger? {
        if (rates == null || currency == "CNY") return minor.toBigInteger() * SCALE
        val table = rates as? FxRateTable
        val precise = table?.onlineNanos?.get(currency)
        val nanos = when {
            table != null && currency in table.manual -> table.manual[currency]?.takeIf { com.subtrack.domain.model.Limits.isValidFxRate(it) }?.times(10_000_000L)
            FxPrecision.valid(precise) -> precise
            table != null -> null
            else -> rates[currency]?.takeIf { com.subtrack.domain.model.Limits.isValidFxRate(it) }?.times(10_000_000L)
        } ?: return null
        return minor.toBigInteger() * nanos.toBigInteger()
    }
    fun annualScaled(s: Subscription, rates: Map<String, Long>?): BigInteger? =
        scaled(s.priceMinor, s.currency, rates)?.let { Annualizer.annualizeExact(it, s.cycle, s.customCycleDays) }
    fun sumAnnual(subs: List<Subscription>, rates: Map<String, Long>?): BigInteger =
        subs.filter { it.isForecastIncluded }.fold(BigInteger.ZERO) { acc, s -> acc + (annualScaled(s, rates) ?: BigInteger.ZERO) }
    fun round(value: BigInteger): BigInteger = (value + SCALE / 2.toBigInteger()) / SCALE
    fun display(value: BigInteger): Long = AmountMath.display(round(value))
    fun monthly(value: BigInteger): Long = AmountMath.display((value + SCALE * 6.toBigInteger()) / (SCALE * 12.toBigInteger()))
    fun hasOnline(rates: Map<String, Long>?): Boolean = (rates as? FxRateTable)?.onlineNanos?.isNotEmpty() == true
}
