package com.subtrack.data.repository

import com.subtrack.core.time.AppClock
import com.subtrack.data.db.CategoryDao
import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.PaymentRecordDao
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubTrackDatabase
import com.subtrack.data.db.SubscriptionDao
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.repository.PaymentRecord
import com.subtrack.domain.repository.SubscriptionRepository
import com.subtrack.sync.DirectSyncWriteGateway
import com.subtrack.sync.SyncWriteGateway
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

class SubscriptionRepositoryImpl(
    private val subscriptionDao: SubscriptionDao,
    private val paymentDao: PaymentRecordDao,
    private val categoryDao: CategoryDao,
    private val clock: AppClock,
    private val database: SubTrackDatabase? = null,
    /** Default is a pass-through so sync-off behaviour is byte-for-byte unchanged. Bound only when a vault is active. */
    private val syncGateway: SyncWriteGateway = DirectSyncWriteGateway,
    /** T1: outer business transactions are coordinated (vault lock → dedicated tx thread → Room) when sync is active. */
    private val transactionCoordinator: com.subtrack.sync.SyncTransactionCoordinator =
        com.subtrack.sync.SyncTransactionCoordinator.PassThrough,
) : SubscriptionRepository {

    override fun observeAll(): Flow<List<Subscription>> =
        subscriptionDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): Subscription? = subscriptionDao.findById(id)?.toDomain()

    override suspend fun upsert(subscription: Subscription) {
        require(com.subtrack.domain.model.Limits.isValidPriceMinor(subscription.priceMinor)) { "金额超出范围" }
        require(subscription.cycle != BillingCycleType.CUSTOM_DAYS || com.subtrack.domain.model.Limits.isValidCustomCycleDays(subscription.customCycleDays)) { "自定义周期无效" }
        val now = clock.nowMillis()
        val createdAt = subscriptionDao.findById(subscription.id)?.createdAtUtcMillis ?: now
        val entity = subscription.toEntity(now).copy(createdAtUtcMillis = createdAt)
        syncGateway.subscription(entity, entity.deletedAtUtcMillis != null) { subscriptionDao.upsert(entity) }
    }

    override suspend fun archive(id: String) {
        val ts = clock.nowMillis()
        val existing = subscriptionDao.findById(id)
        if (existing == null) {
            subscriptionDao.softDelete(id, ts)
        } else {
            val tombstone = existing.copy(deletedAtUtcMillis = ts, updatedAtUtcMillis = ts)
            syncGateway.subscription(tombstone, deleted = true) { subscriptionDao.softDelete(id, ts) }
        }
    }

    override suspend fun recordPayment(subscriptionId: String, paidAt: LocalDate, amountMinor: Long) {
        require(com.subtrack.domain.model.Limits.isValidPaymentMinor(amountMinor)) { "流水金额超出范围" }
        val entity = PaymentRecordEntity(
            id = UUID.randomUUID().toString(),
            subscriptionId = subscriptionId,
            paidAtEpochDay = paidAt.toEpochDay().toInt(),
            amountMinor = amountMinor,
            createdAtUtcMillis = clock.nowMillis(),
        )
        syncGateway.payment(entity) { paymentDao.insert(entity) }
    }

    override suspend fun recordPayments(records: List<PaymentRecord>) {
        val now = clock.nowMillis()
        require(records.all { com.subtrack.domain.model.Limits.isValidPaymentMinor(it.amountMinor) }) { "流水金额超出范围" }
        records.forEach {
            val entity = PaymentRecordEntity(
                id = it.id,
                subscriptionId = it.subscriptionId,
                paidAtEpochDay = it.paidAt.toEpochDay().toInt(),
                amountMinor = it.amountMinor,
                currency = it.currency,
                createdAtUtcMillis = now,
                note = it.note,
            )
            // Notification confirmation / renewal go through here; enqueue each payment with its ledger write in one tx.
            syncGateway.payment(entity) { paymentDao.insert(entity) }
        }
    }

    override suspend fun <T> runInTransaction(block: suspend () -> T): T {
        val db = database ?: return block()
        return transactionCoordinator.run { db.withTransaction { block() } }
    }

    override suspend fun totalPaid(subscriptionId: String): Long =
        com.subtrack.domain.stats.CurrencyConverter.paymentsCnySummary(paymentsOf(subscriptionId), null).first

    override suspend fun paymentsSummary(from: LocalDate, to: LocalDate): Pair<Long, Int> {
        val start = from.toEpochDay().toInt()
        val end = to.toEpochDay().toInt()
        return com.subtrack.domain.stats.CurrencyConverter.paymentsCnySummary(paymentsBetween(from, to), null)
    }

    override suspend fun paymentsBetween(from: LocalDate, to: LocalDate): List<PaymentRecord> =
        paymentDao.betweenForActiveSubs(from.toEpochDay().toInt(), to.toEpochDay().toInt())
            .map { it.toDomain() }

    override suspend fun firstPaymentDate(): LocalDate? =
        paymentDao.firstPaidEpochDay()?.let { LocalDate.ofEpochDay(it.toLong()) }

    override suspend fun paymentsOf(subscriptionId: String): List<PaymentRecord> =
        paymentDao.bySubscription(subscriptionId).map { it.toDomain() }

    override suspend fun allPayments(): List<PaymentRecord> =
        paymentDao.all().map { it.toDomain() }

    override suspend fun insertPayments(records: List<PaymentRecord>) {
        require(records.all { com.subtrack.domain.model.Limits.isValidPaymentMinor(it.amountMinor) }) { "流水金额超出范围" }
        val now = clock.nowMillis()
        // Import path: each imported payment is enqueued with its ledger write so imports also reach other devices.
        records.forEach {
            val entity = PaymentRecordEntity(
                id = it.id,
                subscriptionId = it.subscriptionId,
                paidAtEpochDay = it.paidAt.toEpochDay().toInt(),
                amountMinor = it.amountMinor,
                currency = it.currency,
                createdAtUtcMillis = now,
                note = it.note,
            )
            syncGateway.payment(entity) { paymentDao.insert(entity) }
        }
    }

    override suspend fun allIds(): Set<String> = subscriptionDao.allIds().toSet()

    override suspend fun observeAllSnapshot(): List<Subscription> =
        observeAll().first()

    override suspend fun lastPaidAmounts(): Map<String, Long> =
        paymentDao.latestAmounts().associate { it.subscriptionId to it.amountMinor }

    override suspend fun seedIfEmpty() {
        if (categoryDao.count() == 0) categoryDao.insertAll(defaultCategories())
        if (subscriptionDao.count() > 0) return

        val today = clock.today()
        val now = clock.nowMillis()
        val seeds = listOf(
            seed("music", "网易云音乐", "music", 1_500L, BillingCycleType.MONTHLY, today.plusDays(1), today),
            seed("ai", "ChatGPT Plus", "ai", 14_000L, BillingCycleType.MONTHLY, today.plusDays(3), today),
            seed("cloud", "iCloud+ 50GB", "cloud", 2_100L, BillingCycleType.MONTHLY, today.plusDays(7), today),
            seed("server", "VPS · 东京节点", "server", 4_500L, BillingCycleType.MONTHLY, today.plusDays(10), today),
            seed("telecom", "家庭宽带 500M", "telecom", 9_900L, BillingCycleType.YEARLY, today.plusDays(20), today),
            seed("video", "腾讯视频 VIP", "video", 2_500L, BillingCycleType.MONTHLY, today.minusDays(2), today),
        )
        seeds.forEach { subscriptionDao.upsert(it.copy(createdAtUtcMillis = now, updatedAtUtcMillis = now)) }
    }

    private fun seed(
        categoryId: String,
        name: String,
        idSuffix: String,
        priceMinor: Long,
        cycle: BillingCycleType,
        next: LocalDate,
        today: LocalDate,
    ): SubscriptionEntity = SubscriptionEntity(
        id = "seed-$idSuffix",
        name = name,
        categoryId = categoryId,
        billingCycleType = cycle.name,
        anchorDateEpochDay = (if (cycle == BillingCycleType.YEARLY) today.minusYears(1) else today.minusMonths(1))
            .toEpochDay().toInt(),
        nextChargeDateEpochDay = next.toEpochDay().toInt(),
        priceMinor = priceMinor,
        currency = "CNY",
        status = SubscriptionStatus.ACTIVE.name,
        autoRenew = true,
        reminderOffsetsCsv = "3,0",
        createdAtUtcMillis = 0,
        updatedAtUtcMillis = 0,
    )

    private fun defaultCategories(): List<CategoryEntity> = listOf(
        CategoryEntity("video", "影音娱乐", "play", 0xFF007AFF.toInt(), true, 0),
        CategoryEntity("music", "音乐音频", "music", 0xFF32ADE6.toInt(), true, 1),
        CategoryEntity("cloud", "云存储", "cloud", 0xFF30B0C7.toInt(), true, 2),
        CategoryEntity("ai", "AI 服务", "sparkle", 0xFFFF9500.toInt(), true, 3),
        CategoryEntity("server", "软件与服务器", "terminal", 0xFF34C759.toInt(), true, 4),
        CategoryEntity("telecom", "通信宽带", "wifi", 0xFF5AC8FA.toInt(), true, 5),
        CategoryEntity("study", "学习阅读", "book", 0xFFA2845E.toInt(), true, 6),
        CategoryEntity("health", "健康生活", "heart", 0xFFFF2D55.toInt(), true, 7),
        CategoryEntity("other", "其他", "grid", 0xFF8E8E93.toInt(), true, 8),
    )

    private fun PaymentRecordEntity.toDomain(): PaymentRecord = PaymentRecord(
        id = id,
        subscriptionId = subscriptionId,
        paidAt = LocalDate.ofEpochDay(paidAtEpochDay.toLong()),
        amountMinor = amountMinor,
        currency = currency,
        note = note,
    )

    private fun SubscriptionEntity.toDomain(): Subscription = Subscription(
        id = id,
        name = name,
        categoryId = categoryId,
        paymentMethodId = paymentMethodId,
        cycle = runCatching { BillingCycleType.valueOf(billingCycleType) }.getOrDefault(BillingCycleType.MONTHLY),
        customCycleDays = customCycleDays,
        anchorDate = LocalDate.ofEpochDay(anchorDateEpochDay.toLong()),
        nextChargeDate = nextChargeDateEpochDay?.let { LocalDate.ofEpochDay(it.toLong()) },
        priceMinor = priceMinor,
        currency = currency,
        status = runCatching { SubscriptionStatus.valueOf(status) }.getOrDefault(SubscriptionStatus.ACTIVE),
        autoRenew = autoRenew,
        trialEndDate = trialEndEpochDay?.let { LocalDate.ofEpochDay(it.toLong()) },
        canceledAt = canceledAtEpochDay?.let { LocalDate.ofEpochDay(it.toLong()) },
        // 区分"未设置"（null = 旧数据/新建，用全局默认 [3,0]）与"明确为空"
        // （"" = 用户关掉了全部提醒，往返后必须保持为空）。
        // 之前的 takeIf 版本会把 "" 读回 [3,0]，用户关掉的提醒在重启后自动复活。
        reminderOffsets = when (reminderOffsetsCsv) {
            null -> listOf(3, 0)
            else -> com.subtrack.domain.model.AppSettings.sanitizeOffsets(
                reminderOffsetsCsv.split(",").mapNotNull { it.trim().toIntOrNull() },
            )
        },
        mutedUntil = reminderMutedUntilEpochDay?.let { LocalDate.ofEpochDay(it.toLong()) },
        note = note,
        deletedAtUtcMillis = deletedAtUtcMillis,
    )

    private fun Subscription.toEntity(now: Long): SubscriptionEntity = SubscriptionEntity(
        id = id,
        name = name,
        categoryId = categoryId,
        paymentMethodId = paymentMethodId,
        billingCycleType = cycle.name,
        customCycleDays = customCycleDays,
        anchorDateEpochDay = anchorDate.toEpochDay().toInt(),
        nextChargeDateEpochDay = nextChargeDate?.toEpochDay()?.toInt(),
        priceMinor = priceMinor,
        currency = currency,
        status = status.name,
        autoRenew = autoRenew,
        trialEndEpochDay = trialEndDate?.toEpochDay()?.toInt(),
        canceledAtEpochDay = canceledAt?.toEpochDay()?.toInt(),
        reminderOffsetsCsv = reminderOffsets.joinToString(","),
        reminderMutedUntilEpochDay = mutedUntil?.toEpochDay()?.toInt(),
        note = note,
        createdAtUtcMillis = now,
        updatedAtUtcMillis = now,
        deletedAtUtcMillis = deletedAtUtcMillis,
    )
}
