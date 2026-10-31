package com.subtrack.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore(name = "subtrack_settings")

/**
 * 全局设置持久化。
 *
 * 约定：**每个 AppSettings 字段都必须在这里同时有读与写**。漏一个的表现是"改了没保存"——
 * 不报错，用户重启后才发现设置没了（v1.1 加 L2 字段时真实踩过）。
 * 提醒偏移存 CSV 而不是 stringSet，因为要保序（30,7,3,0）。
 */
class SettingsStore(private val context: Context) {

    private val keyOffsets = stringPreferencesKey("default_reminder_offsets")
    private val keyTime = stringPreferencesKey("reminder_time")
    private val keyBannerDismissed = booleanPreferencesKey("notifications_banner_dismissed")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyAppLock = booleanPreferencesKey("app_lock_enabled")

    // L2 增强（漏写任何一个键 = 用户改了设置重启就丢）
    private val keyBudget = longPreferencesKey("monthly_budget_minor")
    private val keyPriceWatch = booleanPreferencesKey("price_watch_enabled")
    private val keyHealthCheck = booleanPreferencesKey("health_check_enabled")
    private val keyMultiCurrency = booleanPreferencesKey("multi_currency_enabled")
    private val keyFxRates = stringPreferencesKey("fx_rates")

    // 保留一个整数键用于将来的迁移标记，避免 DataStore 里没有 schema 版本可用
    private val keySchema = intPreferencesKey("schema_version")
    private val keyTemplatesEnabled = booleanPreferencesKey("network_templates_enabled")
    private val keyTemplatesUrl = stringPreferencesKey("network_templates_url")
    private val keyFxEnabled = booleanPreferencesKey("network_fx_enabled")
    private val keyFxUrl = stringPreferencesKey("network_fx_url")
    private val keyUpdateEnabled = booleanPreferencesKey("network_update_enabled")
    private val keyUpdateUrl = stringPreferencesKey("network_update_manifest_url")
    private val keyAiEnabled = booleanPreferencesKey("network_ai_enabled")
    private val keyAiUrl = stringPreferencesKey("network_ai_url")
    private val keyAiModel = stringPreferencesKey("network_ai_model")
    private val keyAiModels = stringPreferencesKey("network_ai_models_v1")
    private val keyAiKeyAlias = stringPreferencesKey("network_ai_key_alias")

    private val keyAutoRates = stringPreferencesKey("network_fx_rates")
    private val keyFxDate = stringPreferencesKey("network_fx_date")
    private val keyFxFetched = longPreferencesKey("network_fx_fetched")
    private val keyFxPrecise = stringPreferencesKey("network_fx_nanos_v1")
    private val keyFxBase = stringPreferencesKey("network_fx_base")
    private val keyFxSource = stringPreferencesKey("network_fx_source")
    private val keyFxAuto = booleanPreferencesKey("network_fx_auto")
    private val keyFxLastAttempt = longPreferencesKey("network_fx_auto_attempt")
    private val keyFxAutoError = booleanPreferencesKey("network_fx_auto_error")

    private val keyRevision = longPreferencesKey("network_connection_revision")

    fun observe(): Flow<AppSettings> = context.dataStore.data.map { decode(it) }

    private fun decode(prefs: androidx.datastore.preferences.core.Preferences): AppSettings =
        AppSettings(
            defaultReminderOffsets = prefs[keyOffsets]
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.let { AppSettings.sanitizeOffsets(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: AppSettings().defaultReminderOffsets,
            reminderTime = prefs[keyTime]
                ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                ?: LocalTime.of(9, 0),
            notificationsBannerDismissed = prefs[keyBannerDismissed] ?: false,
            themeMode = prefs[keyThemeMode]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            appLockEnabled = prefs[keyAppLock] ?: false,
            // 预算是可空字段：0 或缺失都当"未设置"，避免 0 元预算把一切都判成超支
            monthlyBudgetMinor = prefs[keyBudget]?.takeIf { it > 0L },
            priceWatchEnabled = prefs[keyPriceWatch] ?: false,
            healthCheckEnabled = prefs[keyHealthCheck] ?: false,
            multiCurrencyEnabled = prefs[keyMultiCurrency] ?: false,
            fxRates = decodeRates(prefs[keyFxRates]),
            network = com.subtrack.domain.model.NetworkSettings(
                templatesEnabled = prefs[keyTemplatesEnabled] ?: false,
                templatesBaseUrl = prefs[keyTemplatesUrl] ?: "",
                fxEnabled = prefs[keyFxEnabled] ?: false,
                fxBaseUrl = prefs[keyFxUrl] ?: "",
                updateEnabled = prefs[keyUpdateEnabled] ?: false,
                updateManifestUrl = prefs[keyUpdateUrl] ?: "",
                aiEnabled = prefs[keyAiEnabled] ?: false,
                aiBaseUrl = prefs[keyAiUrl] ?: "",
                aiModel = prefs[keyAiModel] ?: "",
                aiModels = decodeModels(prefs[keyAiModels]),
                aiApiKeyAlias = prefs[keyAiKeyAlias],
                autoFxRates = decodeRates(prefs[keyAutoRates]),
                autoFxPreciseRates = decodePreciseRates(prefs[keyFxPrecise]),
                fxBase = prefs[keyFxBase] ?: "",
                fxSource = prefs[keyFxSource] ?: "",
                fxAutoEnabled = prefs[keyFxAuto] ?: false,
                fxLastAutoAttempt = prefs[keyFxLastAttempt] ?: 0L,
                fxAutoError = prefs[keyFxAutoError] ?: false,
                fxDate = prefs[keyFxDate] ?: "",
                fxFetchedAt = prefs[keyFxFetched] ?: 0,
                connectionRevision = prefs[keyRevision] ?: 0,
            ),
        )

    suspend fun update(settings: AppSettings) {
        require(settings.fxRates.values.all { com.subtrack.domain.model.Limits.isValidFxRate(it) }) { "汇率超出范围" }
        context.dataStore.edit { prefs ->
            prefs[keyOffsets] = AppSettings.sanitizeOffsets(settings.defaultReminderOffsets)
                .joinToString(",")
            prefs[keyTime] = settings.reminderTime.toString()
            prefs[keyBannerDismissed] = settings.notificationsBannerDismissed
            prefs[keyThemeMode] = settings.themeMode.name
            prefs[keyAppLock] = settings.appLockEnabled

            val budget = settings.monthlyBudgetMinor
            if (budget != null && budget > 0L) prefs[keyBudget] = budget else prefs.remove(keyBudget)
            prefs[keyPriceWatch] = settings.priceWatchEnabled
            prefs[keyHealthCheck] = settings.healthCheckEnabled
            prefs[keyMultiCurrency] = settings.multiCurrencyEnabled
            prefs[keyFxRates] = encodeRates(settings.fxRates)
            prefs[keySchema] = 3
        }
    }

    // Network changes are patches inside DataStore's transaction, never stale whole-AppSettings writes.
    suspend fun updateNetwork(change: (com.subtrack.domain.model.NetworkSettings) -> com.subtrack.domain.model.NetworkSettings) {
        context.dataStore.edit { prefs ->
            val old = decode(prefs).network
            val n = change(old)
            writeNetwork(prefs, n)
        }
    }

    private fun writeNetwork(prefs: androidx.datastore.preferences.core.MutablePreferences, n: com.subtrack.domain.model.NetworkSettings) {
            prefs[keyAutoRates] = encodeRates(n.autoFxRates)
            prefs[keyFxPrecise] = encodePreciseRates(n.autoFxPreciseRates)
            prefs[keyFxBase] = n.fxBase
            prefs[keyFxSource] = n.fxSource
            prefs[keyFxAuto] = n.fxAutoEnabled
            prefs[keyFxLastAttempt] = n.fxLastAutoAttempt
            prefs[keyFxAutoError] = n.fxAutoError
            prefs[keyFxDate] = n.fxDate
            prefs[keyFxFetched] = n.fxFetchedAt
            prefs[keyTemplatesEnabled] = n.templatesEnabled
            prefs[keyTemplatesUrl] = n.templatesBaseUrl.trim()
            prefs[keyFxEnabled] = n.fxEnabled
            prefs[keyFxUrl] = n.fxBaseUrl.trim()
            prefs[keyUpdateEnabled] = n.updateEnabled
            prefs[keyUpdateUrl] = n.updateManifestUrl.trim()
            prefs[keyAiEnabled] = n.aiEnabled
            prefs[keyAiUrl] = n.aiBaseUrl.trim()
            prefs[keyAiModel] = n.aiModel.trim()
            prefs[keyAiModels] = encodeModels(n.aiModels)
            if (n.aiApiKeyAlias.isNullOrBlank()) prefs.remove(keyAiKeyAlias) else prefs[keyAiKeyAlias] = n.aiApiKeyAlias
        prefs[keyRevision] = n.connectionRevision
    }

    /** `USD=720;EUR=780` — 按 code 排序，保证同一份数据每次序列化结果一致。 */
    internal fun encodeRates(rates: Map<String, Long>): String =
        rates.entries
            .filter { com.subtrack.domain.model.Limits.isValidFxRate(it.value) }
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }

    internal fun decodeRates(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val code = entry.substring(0, idx).trim()
            val value = entry.substring(idx + 1).trim().toLongOrNull()
            if (code.isEmpty() || value == null || !com.subtrack.domain.model.Limits.isValidFxRate(value)) null else code to value
        }.toMap()
    }

    /** 模型名列表：用 ';' 分隔；过滤掉空值、控制字符与超长项，最多 200 个。 */
    internal fun encodeModels(models: List<String>): String = models
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length <= 120 && it.none { c -> c.isISOControl() } && ';' !in it }
        .distinct()
        .take(200)
        .joinToString(";")

    internal fun decodeModels(raw: String?): List<String> =
        raw.orEmpty().split(';').map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 120 && it.none { c -> c.isISOControl() } }
            .distinct()
            .take(200)

    internal fun encodePreciseRates(rates: Map<String, Long>): String = rates.entries
        .filter { it.key.matches(Regex("[A-Z]{3}")) && com.subtrack.domain.stats.FxPrecision.valid(it.value) }
        .sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    internal fun decodePreciseRates(raw: String?): Map<String, Long> = raw.orEmpty().split(';').mapNotNull { entry ->
        val parts = entry.split('=')
        if (parts.size != 2 || !parts[0].matches(Regex("[A-Z]{3}"))) return@mapNotNull null
        val value = parts[1].toLongOrNull()
        if (com.subtrack.domain.stats.FxPrecision.valid(value)) parts[0] to value!! else null
    }.toMap()
}
