package com.subtrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subscription",
    indices = [
        Index(value = ["status", "nextChargeDateEpochDay"]),
        Index("categoryId"),
        Index("deletedAtUtcMillis"),
    ],
)
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String,
    val paymentMethodId: String? = null,
    val billingCycleType: String,
    val customCycleDays: Int? = null,
    val anchorDateEpochDay: Int,
    val nextChargeDateEpochDay: Int? = null,
    val priceMinor: Long,
    val currency: String = "CNY",
    val status: String,
    val autoRenew: Boolean = true,
    val trialEndEpochDay: Int? = null,
    val canceledAtEpochDay: Int? = null,
    val reminderOffsetsCsv: String? = "3,0",
    val reminderMutedUntilEpochDay: Int? = null,
    val note: String? = null,
    val createdAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
    val deletedAtUtcMillis: Long? = null,
)

@Entity(tableName = "category", indices = [Index("sortOrder")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconKey: String,
    val colorArgb: Int,
    val isBuiltIn: Boolean = true,
    val sortOrder: Int,
)

@Entity(
    tableName = "payment_record",
    indices = [
        Index("subscriptionId"),
        Index("paidAtEpochDay"),
        Index(value = ["subscriptionId", "paidAtEpochDay"], unique = true),
    ],
)
data class PaymentRecordEntity(
    @PrimaryKey val id: String,
    val subscriptionId: String,
    val paidAtEpochDay: Int,
    val amountMinor: Long,
    val currency: String = "CNY",
    val source: String = "USER_CONFIRMED",
    val createdAtUtcMillis: Long,
    val note: String? = null,
)

@Entity(
    tableName = "scheduled_reminder",
    indices = [Index("subscriptionId"), Index(value = ["state", "fireAtUtcMillis"])],
)
data class ScheduledReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: String,
    val kind: String,
    val offsetDays: Int,
    val chargeDateEpochDay: Int,
    val fireAtUtcMillis: Long,
    val state: String = "PENDING",
    val createdAtUtcMillis: Long,
)

@Entity(tableName = "price_history", indices = [Index("subscriptionId")])
data class PriceHistoryEntity(
    @PrimaryKey val id: String,
    val subscriptionId: String,
    val oldPriceMinor: Long,
    val newPriceMinor: Long,
    val effectiveFromEpochDay: Int,
    val recordedAtUtcMillis: Long,
)

@Entity(tableName = "metrics_event", indices = [Index(value = ["eventName", "occurredAtUtcMillis"])])
data class MetricsEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventName: String,
    val occurredAtUtcMillis: Long,
    val appVersionName: String,
)
