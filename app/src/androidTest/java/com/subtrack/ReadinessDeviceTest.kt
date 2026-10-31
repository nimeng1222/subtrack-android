package com.subtrack

import android.content.pm.PackageManager
import android.provider.Settings
import com.subtrack.data.settings.SettingsState
import com.subtrack.ui.settings.*
import com.subtrack.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ReadinessDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext

    @Test fun battery_route_uses_operable_general_page_without_exemption_permission() {
        val intent = batterySettingsIntent(context, BatteryDestination.OPTIMIZATION_LIST)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
        assertNull(intent.data)
        assertNotNull(intent.resolveActivity(context.packageManager))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }

    @Test fun missing_battery_handlers_return_explicit_instructions() {
        val attempts = mutableListOf<String?>()
        val result = java.util.concurrent.atomic.AtomicReference<BatterySettingsResult>()
        Round2Instrumentation.instance.runOnMainSync {
            result.set(openBatterySettings(context) { intent ->
                attempts += intent.action
                throw android.content.ActivityNotFoundException()
            })
        }
        assertEquals(listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS), attempts)
        assertNull(result.get().destination)
        assertTrue(result.get().message.contains("手动进入"))
    }

    @Test fun production_widget_pipeline_publishes_rate_and_switch_changes_without_subscription_writes() = runBlocking {
        val c = (context.applicationContext as SubTrackApp).container
        val original = c.awaitSettings()
        val snapshot = c.repository.observeAllSnapshot()
        val seen = Channel<Pair<WidgetInput, WidgetCurrency>>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.IO) {
            refreshWidgets(c.widgetInputs) { input ->
                val current = c.settings
                seen.send(input to WidgetCurrency(current.multiCurrencyEnabled, current.fxRates))
            }
        }
        suspend fun waitFor(wanted: WidgetCurrency) = withTimeout(5000) {
            while (true) {
                val (input, published) = seen.receive()
                if (input.currency == wanted) { assertEquals(wanted, published); break }
            }
        }
        try {
            val first = original.copy(multiCurrencyEnabled = true, fxRates = original.fxRates + ("USD" to 100L))
            c.settingsStore.update(first); waitFor(WidgetCurrency(true, first.fxRates))
            val second = first.copy(fxRates = first.fxRates + ("USD" to 200L))
            c.settingsStore.update(second); waitFor(WidgetCurrency(true, second.fxRates))
            c.settingsStore.update(second.copy(multiCurrencyEnabled = false)); waitFor(WidgetCurrency(false, second.fxRates))
            assertEquals(snapshot, c.repository.observeAllSnapshot())
        } finally {
            c.settingsStore.update(original)
            withTimeout(5000) { c.settingsState.filterIsInstance<SettingsState.Loaded>().first { it.settings == original } }
            observer.cancelAndJoin(); seen.close()
        }
    }
}
