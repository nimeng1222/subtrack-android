package com.subtrack.domain.model

/** Network feature switches and endpoints. Secrets are never serialized to backups or sync payloads. */
data class NetworkSettings(
    val templatesEnabled: Boolean = false,
    val templatesBaseUrl: String = "",
    val fxEnabled: Boolean = false,
    val fxBaseUrl: String = "",
    val updateEnabled: Boolean = false,
    val updateManifestUrl: String = "",
    val aiEnabled: Boolean = false,
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    /** 从服务 /models 拉到并勾选可用的模型；第一个外的用 Ai 页的模型切换。 */
    val aiModels: List<String> = emptyList(),
    val aiApiKeyAlias: String? = null,
    val autoFxRates: Map<String, Long> = emptyMap(),
    /** Online CNY per currency unit, scaled by 1e9; never included in portable backups. */
    val autoFxPreciseRates: Map<String, Long> = emptyMap(),
    val fxBase: String = "",
    val fxSource: String = "",
    val fxAutoEnabled: Boolean = false,
    val fxLastAutoAttempt: Long = 0,
    val fxAutoError: Boolean = false,
    val fxDate: String = "",
    val fxFetchedAt: Long = 0,
    val connectionRevision: Long = 0,
)

enum class NetworkStatus { NOT_CONFIGURED, DISABLED, CONNECTING, AVAILABLE, FAILED, OFFLINE_CACHE }

data class SubscriptionTemplate(
    val id: String,
    val serviceName: String,
    val region: String? = null,
    val plan: String? = null,
    val currency: String? = null,
    val billingPeriod: String? = null,
    val referencePriceMinor: Long? = null,
    val taxIncluded: Boolean? = null,
    val trialTerms: String? = null,
    val websiteUrl: String? = null,
    val cancellationUrl: String? = null,
    val sourceUrl: String? = null,
    val verifiedAt: String? = null,
    val dataVersion: String? = null,
)

data class FxQuote(
    val base: String,
    val quote: String,
    val ratePpm: Long,
    val asOfDate: String,
    val fetchedAtUtcMillis: Long,
    val sourceUrl: String,
    val isStale: Boolean = false,
)

data class UpdateManifest(
    val packageName: String,
    val channel: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
    val signature: String? = null,
)

data class AiPriceReference(
    val service: String,
    val plan: String?,
    val region: String?,
    val currency: String?,
    val amountMinor: Long?,
    val billingPeriod: String?,
    val taxTerms: String?,
    val sourceUrl: String?,
    val sourceType: String,
    val pageDate: String?,
    val retrievedAtUtcMillis: Long,
    val verification: String,
    val evidence: String?,
    val priceAdoptionAllowed: Boolean = false,
)
