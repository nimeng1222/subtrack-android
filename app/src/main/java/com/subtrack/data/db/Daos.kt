package com.subtrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription WHERE deletedAtUtcMillis IS NULL ORDER BY nextChargeDateEpochDay IS NULL, nextChargeDateEpochDay ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SubscriptionEntity?

    @Upsert
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("UPDATE subscription SET deletedAtUtcMillis = :ts, updatedAtUtcMillis = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("SELECT COUNT(*) FROM subscription")
    suspend fun count(): Int

    @Query("SELECT id FROM subscription")
    suspend fun allIds(): List<String>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Query("SELECT * FROM category ORDER BY sortOrder ASC")
    suspend fun all(): List<CategoryEntity>

    /** 仅用于种子/初始分类：已存在的 id 保持原值（IGNORE）。同步更新必须走 [upsert]。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<CategoryEntity>)

    /**
     * 同步来的分类更新必须真正改写既有行（F3）：旧实现用 insertAll(IGNORE) 导致改名/属性不生效却推进 heads。
     * 种子插入与同步更新语义分开，避免把“首次种子”与“远端顺序修改”混为一谈。
     */
    @Upsert
    suspend fun upsert(entity: CategoryEntity)
}

@Dao
interface PaymentRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PaymentRecordEntity)

    @Query("SELECT * FROM payment_record WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PaymentRecordEntity?

    @Query("SELECT * FROM payment_record WHERE subscriptionId = :subscriptionId ORDER BY paidAtEpochDay DESC")
    fun observeBySubscription(subscriptionId: String): Flow<List<PaymentRecordEntity>>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM payment_record WHERE subscriptionId = :subscriptionId")
    suspend fun totalPaid(subscriptionId: String): Long

    /**
     * 实际统计只统计**未删除订阅**的流水：删除确认框承诺"该条历史扣费不再计入统计"，
     * 查询必须兑现这句话，所以 JOIN subscription 排除软删除的记录。
     */
    @Query(
        "SELECT COALESCE(SUM(p.amountMinor), 0) FROM payment_record p " +
            "JOIN subscription s ON s.id = p.subscriptionId " +
            "WHERE s.deletedAtUtcMillis IS NULL AND p.paidAtEpochDay BETWEEN :from AND :to",
    )
    suspend fun sumBetween(from: Int, to: Int): Long

    @Query(
        "SELECT COUNT(*) FROM payment_record p " +
            "JOIN subscription s ON s.id = p.subscriptionId " +
            "WHERE s.deletedAtUtcMillis IS NULL AND p.paidAtEpochDay BETWEEN :from AND :to",
    )
    suspend fun countBetween(from: Int, to: Int): Int

    @Query(
        "SELECT MIN(p.paidAtEpochDay) FROM payment_record p " +
            "JOIN subscription s ON s.id = p.subscriptionId " +
            "WHERE s.deletedAtUtcMillis IS NULL",
    )
    suspend fun firstPaidEpochDay(): Int?

    @Query(
        "SELECT p.* FROM payment_record p " +
            "JOIN subscription s ON s.id = p.subscriptionId " +
            "WHERE s.deletedAtUtcMillis IS NULL AND p.paidAtEpochDay BETWEEN :from AND :to " +
            "ORDER BY p.paidAtEpochDay DESC",
    )
    suspend fun betweenForActiveSubs(from: Int, to: Int): List<PaymentRecordEntity>

    @Query("SELECT * FROM payment_record ORDER BY paidAtEpochDay DESC")
    suspend fun all(): List<PaymentRecordEntity>

    @Query("SELECT * FROM payment_record WHERE subscriptionId = :subscriptionId ORDER BY paidAtEpochDay DESC")
    suspend fun bySubscription(subscriptionId: String): List<PaymentRecordEntity>

    @Query("SELECT * FROM payment_record WHERE subscriptionId = :subscriptionId AND paidAtEpochDay = :paidAtEpochDay LIMIT 1")
    suspend fun findByPeriod(subscriptionId: String, paidAtEpochDay: Int): PaymentRecordEntity?

    @Query("DELETE FROM payment_record WHERE subscriptionId = :subscriptionId AND paidAtEpochDay = :paidAtEpochDay")
    suspend fun deleteByPeriod(subscriptionId: String, paidAtEpochDay: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<PaymentRecordEntity>)

    /** 每条订阅最近一次支付的金额，涨价检测用（文档 §5.4）。 */
    @Query(
        "SELECT subscriptionId, amountMinor FROM payment_record " +
            "WHERE paidAtEpochDay = (SELECT MAX(p2.paidAtEpochDay) FROM payment_record p2 " +
            "WHERE p2.subscriptionId = payment_record.subscriptionId)",
    )
    suspend fun latestAmounts(): List<LatestAmount>
}

/** 投影：订阅 id + 最近一次金额。 */
data class LatestAmount(val subscriptionId: String, val amountMinor: Long)

@Dao
interface ReminderDao {
    @Insert
    suspend fun insertAll(items: List<ScheduledReminderEntity>): List<Long>

    @Query("UPDATE scheduled_reminder SET fireAtUtcMillis = :time WHERE id = :id AND state = 'PENDING'")
    suspend fun reschedule(id: Long, time: Long)

    @Query("UPDATE scheduled_reminder SET state = :state WHERE id = :id AND state = 'PENDING'")
    suspend fun retire(id: Long, state: String)

    @Query("SELECT * FROM scheduled_reminder WHERE id = :id")
    suspend fun byId(id: Long): ScheduledReminderEntity?

    @Query("SELECT * FROM scheduled_reminder WHERE subscriptionId = :subscriptionId AND state = 'PENDING'")
    suspend fun pendingFor(subscriptionId: String): List<ScheduledReminderEntity>

    @Query("SELECT * FROM scheduled_reminder WHERE subscriptionId = :subscriptionId")
    suspend fun allFor(subscriptionId: String): List<ScheduledReminderEntity>

    @Query("DELETE FROM scheduled_reminder WHERE subscriptionId = :subscriptionId AND state = 'PENDING'")
    suspend fun cancelPendingFor(subscriptionId: String)

    /** O5: ALL pending plans regardless of whether the subscription row still exists (orphan/deleted sweep). */
    @Query("SELECT * FROM scheduled_reminder WHERE state = 'PENDING'")
    suspend fun pendingAll(): List<ScheduledReminderEntity>

    @Query("DELETE FROM scheduled_reminder WHERE id = :id AND state = 'PENDING'")
    suspend fun deletePendingById(id: Long)

    /**
     * 只撤销未来的常规计划（重排用）。当天已到点但还没发出的 PENDING 行要**保留**——
     * 否则"打开 App / 编辑"就会把今天该发的提醒删掉；扫描稍后会把它补发出去。
     * SNOOZE（明天再说的持久明日计划）也不在这里删，它由 cancelSnoozeFor 按期次撤销。
     */
    @Query(
        "DELETE FROM scheduled_reminder WHERE subscriptionId = :subscriptionId " +
            "AND state = 'PENDING' AND kind != 'SNOOZE' AND fireAtUtcMillis > :now",
    )
    suspend fun cancelFutureRecurringFor(subscriptionId: String, now: Long)

    /** 同一期只保留一条明日计划：重新点"明天再说"时先撤旧的，防止重复投递。 */
    @Query(
        "DELETE FROM scheduled_reminder WHERE subscriptionId = :subscriptionId " +
            "AND kind = 'SNOOZE' AND chargeDateEpochDay = :chargeDay AND state = 'PENDING'",
    )
    suspend fun cancelSnoozeFor(subscriptionId: String, chargeDay: Int)

    @Query("SELECT * FROM scheduled_reminder WHERE state = 'PENDING' AND fireAtUtcMillis > :now ORDER BY fireAtUtcMillis ASC")
    suspend fun pendingAfter(now: Long): List<ScheduledReminderEntity>

    @Query("SELECT * FROM scheduled_reminder WHERE state = 'PENDING' AND fireAtUtcMillis <= :now ORDER BY fireAtUtcMillis ASC")
    suspend fun pendingDue(now: Long): List<ScheduledReminderEntity>

    @Query("UPDATE scheduled_reminder SET state = 'FIRED' WHERE id = :id")
    suspend fun markFired(id: Long)

    /**
     * 幂等领取：只有仍是 PENDING 的行会被置为 FIRED 并返回 1。
     * 精确闹钟与每日扫描都从这里领取同一条计划，谁先领到谁发，杜绝同一期重复投递。
     */
    @Query("UPDATE scheduled_reminder SET state = 'FIRED' WHERE id = :id AND state = 'PENDING'")
    suspend fun claimFired(id: Long): Int

    @Query("DELETE FROM scheduled_reminder WHERE state != 'PENDING' AND fireAtUtcMillis < :before")
    suspend fun cleanup(before: Long)
}
