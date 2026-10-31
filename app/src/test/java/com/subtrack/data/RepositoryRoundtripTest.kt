package com.subtrack.data

import com.subtrack.core.time.AppClock
import com.subtrack.data.db.CategoryDao
import com.subtrack.data.db.CategoryEntity
import com.subtrack.data.db.LatestAmount
import com.subtrack.data.db.PaymentRecordDao
import com.subtrack.data.db.PaymentRecordEntity
import com.subtrack.data.db.SubscriptionDao
import com.subtrack.data.db.SubscriptionEntity
import com.subtrack.data.repository.SubscriptionRepositoryImpl
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.repository.PaymentRecord
import com.subtrack.domain.state.EditorSaveResult
import com.subtrack.domain.state.saveEditorDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 持久层往返回归：
 * - #21 空 reminderOffsets 经 toEntity → toDomain 往返后仍为空（不得被默认 [3,0] 复活）；
 * - #14 recordPayments 保留币种（确认续费的 USD 流水不得被写成 CNY）。
 */
class RepositoryRoundtripTest {

    private val today = LocalDate.of(2026, 10, 27)
    private val clock = object : AppClock {
        override fun today() = today
        override fun nowMillis() = 0L
    }

    private class FakeSubscriptionDao : SubscriptionDao {
        val entities = LinkedHashMap<String, SubscriptionEntity>()
        private val flow = MutableStateFlow<List<SubscriptionEntity>>(emptyList())

        private fun push() { flow.value = entities.values.toList() }

        override fun observeAll(): Flow<List<SubscriptionEntity>> = flow
        override suspend fun findById(id: String): SubscriptionEntity? = entities[id]
        override suspend fun upsert(entity: SubscriptionEntity) { entities[entity.id] = entity; push() }
        override suspend fun softDelete(id: String, ts: Long) {
            entities[id] = entities[id]!!.copy(deletedAtUtcMillis = ts); push()
        }
        override suspend fun count(): Int = entities.size
        override suspend fun allIds(): List<String> = entities.keys.toList()
    }

    private class FakePaymentDao : PaymentRecordDao {
        val entities = LinkedHashMap<String, PaymentRecordEntity>()
        override suspend fun insert(entity: PaymentRecordEntity) { entities[entity.id] = entity }
        override fun observeBySubscription(subscriptionId: String): Flow<List<PaymentRecordEntity>> =
            MutableStateFlow(emptyList())
        override suspend fun totalPaid(subscriptionId: String): Long = 0L
        override suspend fun sumBetween(from: Int, to: Int): Long = 0L
        override suspend fun countBetween(from: Int, to: Int): Int = 0
        override suspend fun firstPaidEpochDay(): Int? = null
        override suspend fun betweenForActiveSubs(from: Int, to: Int): List<PaymentRecordEntity> =
            entities.values.toList()
        override suspend fun all(): List<PaymentRecordEntity> = entities.values.toList()
        override suspend fun bySubscription(subscriptionId: String): List<PaymentRecordEntity> =
            entities.values.filter { it.subscriptionId == subscriptionId }
        override suspend fun findById(id: String): PaymentRecordEntity? = entities[id]
        override suspend fun findByPeriod(subscriptionId: String, paidAtEpochDay: Int): PaymentRecordEntity? =
            entities.values.firstOrNull { it.subscriptionId == subscriptionId && it.paidAtEpochDay == paidAtEpochDay }
        override suspend fun deleteByPeriod(subscriptionId: String, paidAtEpochDay: Int): Int {
            val victims = entities.values
                .filter { it.subscriptionId == subscriptionId && it.paidAtEpochDay == paidAtEpochDay }
                .map { it.id }
            victims.forEach { entities.remove(it) }
            return victims.size
        }
        override suspend fun insertAll(items: List<PaymentRecordEntity>) { items.forEach { entities[it.id] = it } }
        override suspend fun latestAmounts(): List<LatestAmount> = emptyList()
    }

    private class FakeCategoryDao : CategoryDao {
        private val rows = LinkedHashMap<String, CategoryEntity>()
        override fun observeAll(): Flow<List<CategoryEntity>> = MutableStateFlow(rows.values.toList())
        override suspend fun findById(id: String): CategoryEntity? = rows[id]
        override suspend fun count(): Int = rows.size
        override suspend fun all(): List<CategoryEntity> = rows.values.toList()
        override suspend fun insertAll(items: List<CategoryEntity>) { items.forEach { rows.putIfAbsent(it.id, it) } }
        override suspend fun upsert(entity: CategoryEntity) { rows[entity.id] = entity }
    }

    private fun repo(subDao: FakeSubscriptionDao, payDao: FakePaymentDao) = SubscriptionRepositoryImpl(
        subscriptionDao = subDao,
        paymentDao = payDao,
        categoryDao = FakeCategoryDao(),
        clock = clock,
        database = null, // JVM 测试没有 Room 实例；事务直通
    )

    private fun sub(offsets: List<Int>) = Subscription(
        id = "r1", name = "R", categoryId = "other",
        cycle = BillingCycleType.MONTHLY, anchorDate = today.minusMonths(1),
        nextChargeDate = today.plusDays(1), priceMinor = 1000L,
        reminderOffsets = offsets,
    )

    @Test
    fun `空提醒偏移往返后仍为空`() = runTest {
        val subDao = FakeSubscriptionDao()
        val repository = repo(subDao, FakePaymentDao())

        repository.upsert(sub(offsets = emptyList()))
        // 落库形态：空串（"明确为空"），而不是 null（"未设置"）
        assertEquals("", subDao.entities.getValue("r1").reminderOffsetsCsv)

        // 读回：空列表必须存活，不得被换回 [3,0]
        val readBack = repository.observeAllSnapshot().single()
        assertTrue(readBack.reminderOffsets.isEmpty())
    }

    @Test
    fun `默认与自定义偏移正常往返`() = runTest {
        val subDao = FakeSubscriptionDao()
        val repository = repo(subDao, FakePaymentDao())
        repository.upsert(sub(offsets = listOf(7, 1)))
        assertEquals("7,1", subDao.entities.getValue("r1").reminderOffsetsCsv)
        assertEquals(listOf(7, 1), repository.observeAllSnapshot().single().reminderOffsets)
    }

    @Test
    fun `确认流水保留币种`() = runTest {
        val payDao = FakePaymentDao()
        val repository = repo(FakeSubscriptionDao(), payDao)

        repository.recordPayments(
            listOf(
                PaymentRecord("p1", "r1", today, 10_00L, currency = "USD"),
                PaymentRecord("p2", "r1", today.minusDays(1), 5_00L, currency = "CNY"),
            ),
        )
        assertEquals("USD", payDao.entities.getValue("p1").currency)
        assertEquals("CNY", payDao.entities.getValue("p2").currency)
        // 读回领域模型同样保留
        assertEquals("USD", repository.paymentsOf("r1").first { it.id == "p1" }.currency)
    }

    /**
     * 新建订阅必须补记首期付款：详情页「已付总额」以前永远是 0，用户填了金额却看不到。
     * 编辑同一条时不得再补一笔，否则改个名字就凭空多一次扣费。
     */
    @Test
    fun `新建保存补记一笔首期付款而编辑不再补记`() = runTest {
        val payDao = FakePaymentDao()
        val repository = repo(FakeSubscriptionDao(), payDao)
        val draft = sub(listOf(3, 0)).copy(priceMinor = 25_00L, currency = "USD")

        assertEquals(EditorSaveResult.Saved, repository.saveEditorDraft(null, draft))
        val first = repository.paymentsOf(draft.id).single()
        assertEquals(25_00L, first.amountMinor)
        assertEquals("USD", first.currency)
        assertEquals(draft.anchorDate, first.paidAt)

        assertEquals(EditorSaveResult.Saved, repository.saveEditorDraft(draft, draft.copy(name = "改个名")))
        assertEquals("编辑不能新增流水", 1, repository.paymentsOf(draft.id).size)
    }

    /** 金额为 0 的新建不写流水：0 元不是一次付款，写进去只会污染"笔数"。 */
    @Test
    fun `零元新建不写首期付款`() = runTest {
        val repository = repo(FakeSubscriptionDao(), FakePaymentDao())
        val draft = sub(listOf(3, 0)).copy(priceMinor = 0L)
        assertEquals(EditorSaveResult.Saved, repository.saveEditorDraft(null, draft))
        assertTrue(repository.paymentsOf(draft.id).isEmpty())
    }
}
