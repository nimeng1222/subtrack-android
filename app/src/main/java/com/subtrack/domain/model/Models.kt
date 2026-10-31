package com.subtrack.domain.model

import java.time.LocalDate

/** 计费周期。顺序即录入页“更多周期”的展示顺序。 */
enum class BillingCycleType {
    WEEKLY,
    BIWEEKLY,
    FOUR_WEEKLY,
    MONTHLY,
    QUARTERLY,
    SEMIANNUAL,
    YEARLY,
    CUSTOM_DAYS,
    ONE_TIME;

    /** 是否属于“订阅制”，一次性买断不计入摊销。 */
    val isRecurring: Boolean get() = this != ONE_TIME
}

/** 持久状态只存“用户意图”，待确认是派生的展示态，不落库。 */
enum class SubscriptionStatus { ACTIVE, TRIAL, PAUSED, CANCELED, EXPIRED }

data class Subscription(
    val id: String,
    val name: String,
    val categoryId: String,
    val paymentMethodId: String? = null,
    val cycle: BillingCycleType,
    val customCycleDays: Int? = null,
    val anchorDate: LocalDate,
    val nextChargeDate: LocalDate?,
    val priceMinor: Long,
    val currency: String = "CNY",
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    val autoRenew: Boolean = true,
    val trialEndDate: LocalDate? = null,
    val canceledAt: LocalDate? = null,
    val reminderOffsets: List<Int> = listOf(3, 0),
    val mutedUntil: LocalDate? = null,
    val note: String? = null,
    val deletedAtUtcMillis: Long? = null,
) {
    val isDeleted: Boolean get() = deletedAtUtcMillis != null
    val isForecastIncluded: Boolean
        get() = !isDeleted && (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL)
}

data class MonthlySummary(
    val forecastMonthlyMinor: Long,
    val forecastAnnualMinor: Long,
    val actualMonthlyMinor: Long?,
    val pendingConfirmCount: Int,
    val activeCount: Int,
    val currency: String = "CNY",
)
