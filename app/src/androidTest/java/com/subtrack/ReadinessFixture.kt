package com.subtrack

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import com.subtrack.data.backup.BackupCodec
import com.subtrack.domain.model.*
import com.subtrack.widget.SubTrackWidgetReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import com.subtrack.data.settings.SettingsState
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Manual launcher evidence fixture only; not a test-suite selector and never packaged in the production APK. */
object ReadinessFixture {
    fun run(instrumentation: Instrumentation, mode: String) {
        val targetContext = instrumentation.targetContext
        try {
            instrumentation.waitForIdleSync()
            runBlocking {
                val c = (targetContext.applicationContext as SubTrackApp).container
                val original = c.awaitSettings()
                val file = File(targetContext.filesDir, "readiness-dfix-fixture.json")
                suspend fun publish(s: AppSettings) {
                    c.settingsStore.update(s)
                    withTimeout(5000) { c.settingsState.filterIsInstance<SettingsState.Loaded>().first { it.settings == s } }
                }
                when (mode) {
                    "prepare" -> {
                        check(!file.exists()) { "An existing fixture must be cleaned up first" }
                        c.repository.seedIfEmpty()
                        val id = "D3FX-" + UUID.randomUUID()
                        file.writeText(JSONObject().put("id", id).put("settings", BackupCodec.exportJson(emptyList(), settings = original)).toString())
                        c.repository.upsert(Subscription(id, "D3FX-USD10", "other", cycle = BillingCycleType.MONTHLY,
                            anchorDate = c.clock.today(), nextChargeDate = c.clock.today().plusMonths(1), priceMinor = 1000, currency = "USD"))
                        publish(original.copy(appLockEnabled = false, themeMode = ThemeMode.LIGHT,
                            multiCurrencyEnabled = true, fxRates = original.fxRates + ("USD" to 100L)))
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "FIXTURE_ID=$id\n") })
                    }
                    "pin" -> {
                        instrumentation.startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        delay(800)
                        check(AppWidgetManager.getInstance(targetContext).requestPinAppWidget(ComponentName(targetContext, SubTrackWidgetReceiver::class.java), null, null)) {
                            "Launcher does not support widget pinning"
                        }
                    }
                    "cleanup" -> {
                        check(file.exists()) { "No fixture backup; refusing broad cleanup" }
                        val saved = JSONObject(file.readText())
                        val id = saved.getString("id")
                        check(id.startsWith("D3FX-"))
                        c.repository.archive(id)
                        c.reminderService.cancelFor(id)
                        publish(BackupCodec.importBackup(saved.getString("settings")).settings!!)
                        file.delete()
                    }
                    "fx-real" -> {
                        val url = "https://api.frankfurter.dev/v1/latest?base=EUR"
                        val bytes = com.subtrack.network.NetworkTransport(com.subtrack.BuildConfig.DEBUG).request(url)
                        val quote = com.subtrack.network.FxCodec.decode(bytes.toString(Charsets.UTF_8))
                        check(quote.preciseRates["JPY"] != null) { "JPY missing from real provider response" }
                        val rates = com.subtrack.domain.stats.FxRateTable(emptyMap(), quote.preciseRates)
                        val converted = com.subtrack.domain.stats.CurrencyConverter.toCnyMinor(1_000_000L, "JPY", rates)
                        check(converted != null)
                        instrumentation.sendStatus(0, Bundle().apply {
                            putString("stream", "FX_REAL provider=Frankfurter base=${quote.base} date=${quote.date} JPY10000_CNY_MINOR=$converted\n")
                        })
                    }
                    "sync-wire" -> {
                        val first = com.subtrack.sync.SyncWire("http://127.0.0.1:18081", com.subtrack.network.NetworkTransport(true))
                        val second = com.subtrack.sync.SyncWire("http://127.0.0.1:18081", com.subtrack.network.NetworkTransport(true))
                        val registered = first.register("S".repeat(40), "fixture A")
                        val pair = first.pairingToken(registered.device.token)
                        val paired = second.pair(pair, "fixture B")
                        check(registered.device.tenantId == paired.tenantId && registered.device.deviceId != paired.deviceId)
                        val key = com.subtrack.sync.SyncCrypto.parseRecoveryKey(com.subtrack.sync.SyncCrypto.generateRecoveryKey())
                        val operation = "fixture_operation_0001"
                        try {
                            val encrypted = com.subtrack.sync.SyncCrypto.encrypt(key, paired.tenantId, operation, 1, "isolated test".toByteArray())
                            val item = com.subtrack.sync.SyncOutboxEntity(paired.tenantId, operation, 1, encrypted.nonce, encrypted.ciphertext, 1)
                            val revision = first.upload(registered.device.token, item)
                            val page = second.changes(paired.token, 0)
                            check(page.operations.size == 1 && page.cursor == revision)
                            val recovered = com.subtrack.sync.SyncCrypto.decrypt(key, paired.tenantId, operation, page.operations.single().envelope)
                            check(recovered.toString(Charsets.UTF_8) == "isolated test")
                            recovered.fill(0)
                            first.revoke(registered.device.token, paired.deviceId)
                            check(runCatching { second.devices(paired.token) }.isFailure)
                            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SYNC_WIRE real_local_http revision=$revision paired=2 revoked=true; one Android device, not dual-device sync\n") })
                        } finally { key.fill(0) }
                    }
                    "fx-launcher-prepare" -> {
                        check(!file.exists()) { "An existing fixture must be cleaned up first" }
                        val id = "D3FX-" + UUID.randomUUID()
                        val n = original.network
                        file.writeText(JSONObject().put("id", id).put("settings", BackupCodec.exportJson(emptyList(), settings = original))
                            .put("fxEnabled", n.fxEnabled).put("fxAutoEnabled", n.fxAutoEnabled)
                            .put("fxBase", n.fxBase).put("fxSource", n.fxSource).put("fxDate", n.fxDate)
                            .put("fxFetchedAt", n.fxFetchedAt).put("fxLastAutoAttempt", n.fxLastAutoAttempt)
                            .put("fxAutoError", n.fxAutoError).put("precise", JSONObject(n.autoFxPreciseRates))
                            .put("legacy", JSONObject(n.autoFxRates)).toString())
                        c.repository.upsert(Subscription(id, "FX-JPY-10000", "other", cycle = BillingCycleType.MONTHLY,
                            anchorDate = c.clock.today(), nextChargeDate = c.clock.today().plusMonths(1), priceMinor = 1_000_000, currency = "JPY"))
                        c.settingsStore.updateNetwork { it.copy(fxEnabled = false, fxAutoEnabled = false) }
                        c.settingsStore.update(original.copy(appLockEnabled = false, themeMode = ThemeMode.LIGHT,
                            multiCurrencyEnabled = true, fxRates = original.fxRates + ("JPY" to 5L)))
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "FX_LAUNCHER_PREPARED id=$id\n") })
                    }
                    "fx-launcher-online" -> {
                        check(file.exists())
                        val url = "https://api.frankfurter.dev/v1/latest?base=EUR"
                        val quote = com.subtrack.network.FxCodec.decode(com.subtrack.network.NetworkTransport(true).request(url).toString(Charsets.UTF_8))
                        check(quote.preciseRates.containsKey("JPY"))
                        c.settingsStore.updateNetwork { it.copy(fxEnabled = true, fxAutoEnabled = false,
                            autoFxPreciseRates = quote.preciseRates, autoFxRates = quote.rates, fxBase = quote.base,
                            fxSource = url, fxDate = quote.date, fxFetchedAt = c.clock.nowMillis()) }
                        val current = c.settingsStore.observe().first()
                        c.settingsStore.update(current.copy(fxRates = current.fxRates - "JPY"))
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "FX_LAUNCHER_ONLINE base=${quote.base} date=${quote.date}\n") })
                    }
                    "fx-launcher-cleanup" -> {
                        check(file.exists())
                        val saved = JSONObject(file.readText()); val id = saved.getString("id")
                        check(id.startsWith("D3FX-")); c.repository.archive(id); c.reminderService.cancelFor(id)
                        fun rates(name: String): Map<String, Long> { val o = saved.getJSONObject(name); return o.keys().asSequence().associateWith { o.getLong(it) } }
                        c.settingsStore.update(BackupCodec.importBackup(saved.getString("settings")).settings!!)
                        c.settingsStore.updateNetwork { it.copy(fxEnabled = saved.getBoolean("fxEnabled"), fxAutoEnabled = saved.getBoolean("fxAutoEnabled"),
                            fxBase = saved.getString("fxBase"), fxSource = saved.getString("fxSource"), fxDate = saved.getString("fxDate"),
                            fxFetchedAt = saved.getLong("fxFetchedAt"), fxLastAutoAttempt = saved.getLong("fxLastAutoAttempt"),
                            fxAutoError = saved.getBoolean("fxAutoError"), autoFxPreciseRates = rates("precise"), autoFxRates = rates("legacy")) }
                        file.delete()
                    }
                    else -> error("Expected mode=prepare, pin or cleanup")
                }
            }
            instrumentation.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "FIXTURE $mode OK\n") })
        } catch (e: Exception) {
            instrumentation.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FIXTURE ERROR: ${e.stackTraceToString()}\n") })
        }
    }
}
