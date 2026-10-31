package com.subtrack.widget

import com.subtrack.data.settings.SettingsState
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.stats.Annualizer
import com.subtrack.domain.stats.CurrencyConverter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance

/** A single published currency snapshot is shared by the annual and monthly calculations. */
data class WidgetCurrency(val enabled: Boolean, val rates: Map<String, Long>)
data class WidgetInput(val subscriptions: List<Subscription>, val currency: WidgetCurrency) {
    fun amounts(): Pair<Long, Long> {
        if (currency.enabled && com.subtrack.domain.stats.FxAmounts.hasOnline(currency.rates)) {
            val exact = com.subtrack.domain.stats.FxAmounts.sumAnnual(subscriptions, currency.rates)
            return com.subtrack.domain.stats.FxAmounts.monthly(exact) to com.subtrack.domain.stats.FxAmounts.display(exact)
        }
        val converted = if (!currency.enabled) subscriptions else subscriptions.mapNotNull { sub ->
            CurrencyConverter.toCnyMinor(sub.priceMinor, sub.currency, currency.rates)?.let { sub.copy(priceMinor = it) }
        }
        return Annualizer.forecastMonthlyMinor(converted) to Annualizer.forecastAnnualMinor(converted)
    }
}

fun widgetInputs(subscriptions: Flow<List<Subscription>>, settings: Flow<SettingsState>): Flow<WidgetInput> =
    combine(subscriptions, settings.filterIsInstance<SettingsState.Loaded>()) { subs, loaded ->
        WidgetInput(subs, WidgetCurrency(loaded.settings.multiCurrencyEnabled, loaded.settings.effectiveFxRates))
    }.distinctUntilChanged()

suspend fun refreshWidgets(inputs: Flow<WidgetInput>, refresh: suspend (WidgetInput) -> Unit) {
    inputs.collect { refresh(it) }
}
