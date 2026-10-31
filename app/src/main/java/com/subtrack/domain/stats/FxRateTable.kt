package com.subtrack.domain.stats

/** Manual rates retain the v1 unit (CNY cents per foreign unit). Online rates use 1e-9 CNY. */
class FxRateTable(
    val manual: Map<String, Long>,
    val onlineNanos: Map<String, Long>,
    private val legacyOnline: Map<String, Long> = emptyMap(),
) : Map<String, Long> by (legacyOnline + manual) {
    override fun equals(other: Any?): Boolean = when (other) {
        is FxRateTable -> manual == other.manual && onlineNanos == other.onlineNanos && legacyOnline == other.legacyOnline
        is Map<*, *> -> onlineNanos.isEmpty() && (legacyOnline + manual) == other
        else -> false
    }
    override fun hashCode(): Int = if (onlineNanos.isEmpty()) (legacyOnline + manual).hashCode() else 31 * (legacyOnline + manual).hashCode() + onlineNanos.hashCode()
}

object FxPrecision {
    const val SCALE = 1_000_000_000L
    const val MAX_RATE = 10_000L * SCALE
    fun valid(rate: Long?): Boolean = rate != null && rate in 1..MAX_RATE
}
