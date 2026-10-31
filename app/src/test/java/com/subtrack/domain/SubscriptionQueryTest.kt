package com.subtrack.domain

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.stats.SubscriptionQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SubscriptionQueryTest {

    private fun sub(
        id: String,
        price: Long,
        category: String = "video",
        next: LocalDate? = LocalDate.of(2026, 10, 28),
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ) = Subscription(
        id = id,
        name = id,
        categoryId = category,
        cycle = BillingCycleType.MONTHLY,
        anchorDate = LocalDate.of(2026, 1, 1),
        nextChargeDate = next,
        priceMinor = price,
        status = status,
    )

    private val items = listOf(
        sub("网易云音乐", 15_00, "music", LocalDate.of(2026, 10, 27)),
        sub("ChatGPT", 140_00, "ai", LocalDate.of(2026, 10, 29)),
        sub("VPS", 45_00, "server", LocalDate.of(2026, 11, 5)),
        sub("买断", 599_00, "other", null),
        sub("已取消", 20_00, "video", LocalDate.of(2026, 10, 1), SubscriptionStatus.CANCELED),
    )

    @Test
    fun `关键词匹配名称且忽略大小写`() {
        val r = SubscriptionQuery.apply(items, SubscriptionQuery.Criteria(keyword = "chat"))
        assertEquals(listOf("ChatGPT"), r.map { it.id })
    }

    @Test
    fun `空关键词返回全部`() {
        assertEquals(5, SubscriptionQuery.apply(items, SubscriptionQuery.Criteria()).size)
    }

    @Test
    fun `金额上限`() {
        val r = SubscriptionQuery.apply(items, SubscriptionQuery.Criteria(maxMinor = 30_00L))
        assertEquals(setOf("网易云音乐", "已取消"), r.map { it.id }.toSet())
    }

    @Test
    fun `金额区间`() {
        val r = SubscriptionQuery.apply(
            items,
            SubscriptionQuery.Criteria(minMinor = 30_00L, maxMinor = 100_00L),
        )
        assertEquals(setOf("VPS"), r.map { it.id }.toSet())
    }

    @Test
    fun `分类多选`() {
        val r = SubscriptionQuery.apply(
            items,
            SubscriptionQuery.Criteria(categoryIds = setOf("music", "ai")),
        )
        assertEquals(setOf("网易云音乐", "ChatGPT"), r.map { it.id }.toSet())
    }

    @Test
    fun `组合条件取交集`() {
        val r = SubscriptionQuery.apply(
            items,
            SubscriptionQuery.Criteria(keyword = "V", categoryIds = setOf("server"), maxMinor = 100_00L),
        )
        assertEquals(listOf("VPS"), r.map { it.id })
    }

    @Test
    fun `按到期日排序时无到期日的排最后`() {
        val r = SubscriptionQuery.apply(items, SubscriptionQuery.Criteria())
        assertEquals("买断", r.last().id)
        // 已取消那条的到期日是 10/1，确实最早；排序只看日期不看状态
        assertEquals("已取消", r.first().id)
    }

    @Test
    fun `按金额降序`() {
        val r = SubscriptionQuery.apply(items, SubscriptionQuery.Criteria(sortBy = SubscriptionQuery.SortBy.PRICE_DESC))
        assertEquals(listOf(599_00L, 140_00L, 45_00L, 20_00L, 15_00L), r.map { it.priceMinor })
    }

    @Test
    fun `按金额升序`() {
        val r = SubscriptionQuery.apply(items, SubscriptionQuery.Criteria(sortBy = SubscriptionQuery.SortBy.PRICE_ASC))
        assertEquals(listOf(15_00L, 20_00L, 45_00L, 140_00L, 599_00L), r.map { it.priceMinor })
    }

    @Test
    fun `同额时按名称保证顺序稳定`() {
        val same = listOf(sub("B", 10_00), sub("A", 10_00), sub("C", 10_00))
        val r1 = SubscriptionQuery.apply(same, SubscriptionQuery.Criteria(sortBy = SubscriptionQuery.SortBy.PRICE_DESC))
        val r2 = SubscriptionQuery.apply(same.reversed(), SubscriptionQuery.Criteria(sortBy = SubscriptionQuery.SortBy.PRICE_DESC))
        assertEquals(r1.map { it.id }, r2.map { it.id })
        assertEquals(listOf("A", "B", "C"), r1.map { it.id })
    }

    @Test
    fun `可根据开关排除已结束`() {
        val r = SubscriptionQuery.apply(items, SubscriptionQuery.Criteria(includeEnded = false))
        assertTrue(r.none { it.status == SubscriptionStatus.CANCELED })
        assertEquals(4, r.size)
    }

    @Test
    fun `高级筛选标记`() {
        assertTrue(!SubscriptionQuery.Criteria(keyword = "x").hasAdvancedFilter)
        assertTrue(SubscriptionQuery.Criteria(categoryIds = setOf("a")).hasAdvancedFilter)
        assertTrue(SubscriptionQuery.Criteria(minMinor = 100L).hasAdvancedFilter)
    }

    @Test
    fun `空列表不崩`() {
        assertTrue(SubscriptionQuery.apply(emptyList(), SubscriptionQuery.Criteria()).isEmpty())
    }
}
