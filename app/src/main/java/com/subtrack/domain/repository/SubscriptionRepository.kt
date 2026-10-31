package com.subtrack.domain.repository

import com.subtrack.domain.model.Subscription
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 一条扣费流水，从 Room 的 PaymentRecordEntity 投影出来的纯领域模型。 */
data class PaymentRecord(
    val id: String,
    val subscriptionId: String,
    val paidAt: LocalDate,
    val amountMinor: Long,
    val currency: String = "CNY",
    val note: String? = null,
)

interface SubscriptionRepository {
    fun observeAll(): Flow<List<Subscription>>
    suspend fun get(id: String): Subscription?
    suspend fun upsert(subscription: Subscription)
    suspend fun archive(id: String)
    suspend fun recordPayment(subscriptionId: String, paidAt: LocalDate, amountMinor: Long)

    /** 带币种与来源的落库（统一业务入口用）：逐条保留完整流水对象，不丢 currency。 */
    suspend fun recordPayments(records: List<PaymentRecord>)

    /**
     * 在单个 Room 事务里执行 [block]：任一步失败整体回滚，导入的原子性靠它。
     * 嵌套调用（同一 database/vault，包括中间切过 dispatcher）加入外层事务而不是再开一个；失败向上抛，外层整体回滚。
     */
    suspend fun <T> runInTransaction(block: suspend () -> T): T

    suspend fun totalPaid(subscriptionId: String): Long
    suspend fun paymentsSummary(from: LocalDate, to: LocalDate): Pair<Long, Int>

    /** [from, to] 内、**未删除订阅**的流水明细（预算与实际统计的取数口径）。 */
    suspend fun paymentsBetween(from: LocalDate, to: LocalDate): List<PaymentRecord>

    suspend fun seedIfEmpty()

    /** 库里最早一条流水的日期，用于 AC-36a 判断"覆盖是否满 90 天"。 */
    suspend fun firstPaymentDate(): LocalDate?

    /** 某条订阅的全部流水，详情页的"已付总额"与展开明细要用。 */
    suspend fun paymentsOf(subscriptionId: String): List<PaymentRecord>

    /** 全部流水，备份导出用。 */
    suspend fun allPayments(): List<PaymentRecord>

    /** 批量写入（导入备份用）；已存在的 (subscriptionId, paidAt) 会被数据库唯一约束忽略。 */
    suspend fun insertPayments(records: List<PaymentRecord>)

    /** 当前库中所有订阅 id（含软删除），导入时判断"已存在"用。 */
    suspend fun allIds(): Set<String>

    /** 当前库中的订阅快照（一次性取回），导入时用于区分新增/修正。 */
    suspend fun observeAllSnapshot(): List<Subscription>

    /** 每条订阅最近一次支付的金额（分）。涨价检测用，见 PriceWatch。 */
    suspend fun lastPaidAmounts(): Map<String, Long>
}
