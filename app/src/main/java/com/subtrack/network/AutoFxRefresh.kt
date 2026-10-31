package com.subtrack.network

import com.subtrack.BuildConfig
import com.subtrack.core.time.AppClock
import com.subtrack.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Foreground-process only; opt-in, with a persisted attempt before I/O to bound retries across restarts. */
object AutoFxRefresh {
    const val INTERVAL_MS = 24 * 60 * 60 * 1000L
    /** 默认走 [FxSources.DEFAULT] 的多源回退；自定义地址为空时不再固定某一家。 */
    private data class Config(val enabled: Boolean, val url: String, val revision: Long)

    fun remaining(now: Long, last: Long): Long = when {
        last <= 0L -> 0L
        now < last -> INTERVAL_MS
        now - last >= INTERVAL_MS -> 0L
        else -> INTERVAL_MS - (now - last)
    }

    suspend fun run(store: SettingsStore, clock: AppClock) {
        store.observe().map { it.network }.map { Config(it.fxEnabled && it.fxAutoEnabled, it.fxBaseUrl, it.connectionRevision) }
            .distinctUntilChanged().collectLatest { cfg ->
                if (!cfg.enabled) return@collectLatest
                val transport = NetworkTransport(BuildConfig.DEBUG)
                while (coroutineContext.isActive) {
                    val current = store.observe().first().network
                    val remaining = remaining(clock.nowMillis(), current.fxLastAutoAttempt)
                    if (remaining > 0) { delay(remaining); continue }
                    val now = clock.nowMillis()
                    var allowed = false
                    store.updateNetwork { n ->
                        if (n.fxEnabled && n.fxAutoEnabled && n.fxBaseUrl == cfg.url && n.connectionRevision == cfg.revision &&
                            remaining(now, n.fxLastAutoAttempt) == 0L) {
                            allowed = true; n.copy(fxLastAutoAttempt = now)
                        } else n
                    }
                    if (!allowed) continue
                    try {
                        val (quote, url) = FxFetch.fetch(transport, cfg.url)
                        store.updateNetwork { n ->
                            if (n.fxEnabled && n.fxAutoEnabled && n.fxBaseUrl == cfg.url && n.connectionRevision == cfg.revision)
                                n.copy(autoFxRates = quote.rates, autoFxPreciseRates = quote.preciseRates, fxDate = quote.date,
                                    fxBase = quote.base, fxSource = url, fxFetchedAt = clock.nowMillis(), fxAutoError = false)
                            else n
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        store.updateNetwork { n ->
                            if (n.fxEnabled && n.fxAutoEnabled && n.fxBaseUrl == cfg.url && n.connectionRevision == cfg.revision)
                                n.copy(fxAutoError = true) else n
                        }
                    }
                }
            }
    }
}
