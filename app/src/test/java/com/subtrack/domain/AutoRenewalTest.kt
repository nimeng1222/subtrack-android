package com.subtrack.domain

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.state.AutoRenewal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 1.9.7 的默认模型：订阅存续 = 已付。到期自动补记流水并推进，不需要用户点确认。
 */
class AutoRenewalTest {

    private val today = LocalDate.of(2026, 10, 26)
    private val anchor = LocalDate.of(2026, 1, 26)

    private fun sub(
        next: LocalDate? = today.minusDays(1),
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        cycle: BillingCycleType = BillingCycleType.MONTHLY,
        autoRenew: Boolean = true,
        deleted: Boolean = false,
    ) = Subscription(
        id = "auto-1",
        name = "Kimi",
        categoryId = "ai",
        cycle = cycle,
        anchorDate = anchor,
        nextChargeDate = next,
        priceMinor = 19_900L,
        status = status,
        autoRenew = autoRenew,
        deletedAtUtcMillis = if (deleted) 1L else null,
    )

    @Test
    fun `到期当天自动记一笔并推进一期`() {
        val out = AutoRenewal.plan(sub(next = today), today)!!
        assertEquals(1, out.payments.size)
        assertEquals(today, out.payments.first().paidAt)
        assertEquals(19_900L, out.payments.first().amountMinor)
        assertEquals(LocalDate.of(2026, 11, 26), out.subscription.nextChargeDate)
        assertNull(out.toast)
        assertEquals("auto-1", out.expected?.id)
    }

    @Test
    fun `逾期一期时今天到期的那期也一起记掉`() {
        val out = AutoRenewal.plan(sub(next = LocalDate.of(2026, 9, 26)), today)!!
        assertEquals(
            listOf(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 10, 26)),
            out.payments.map { it.paidAt },
        )
        assertEquals(LocalDate.of(2026, 11, 26), out.subscription.nextChargeDate)
    }

    @Test
    fun `漏掉的多个周期一次补齐`() {
        val out = AutoRenewal.plan(sub(next = LocalDate.of(2026, 7, 26)), today)!!
        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 26),
                LocalDate.of(2026, 8, 26),
                LocalDate.of(2026, 9, 26),
                LocalDate.of(2026, 10, 26),
            ),
            out.payments.map { it.paidAt },
        )
        assertEquals(LocalDate.of(2026, 11, 26), out.subscription.nextChargeDate)
    }

    @Test
    fun `已记过的期次不重复写但日期照常推进`() {
        val out = AutoRenewal.plan(sub(next = today), today, paidDates = setOf(today))!!
        assertTrue(out.payments.isEmpty())
        assertEquals(LocalDate.of(2026, 11, 26), out.subscription.nextChargeDate)
    }

    @Test
    fun `推进后再次运行不再命中`() {
        val first = AutoRenewal.plan(sub(next = today), today)!!
        assertNull(AutoRenewal.plan(first.subscription, today, setOf(today)))
    }

    @Test
    fun `关闭自动续费后不再记账也不再推进`() {
        assertNull(AutoRenewal.plan(sub(next = today, autoRenew = false), today))
    }

    @Test
    fun `非 ACTIVE 状态一律跳过`() {
        listOf(
            SubscriptionStatus.TRIAL,
            SubscriptionStatus.PAUSED,
            SubscriptionStatus.CANCELED,
            SubscriptionStatus.EXPIRED,
        ).forEach { status ->
            assertNull("状态 $status 不应自动续订", AutoRenewal.plan(sub(status = status), today))
        }
    }

    @Test
    fun `一次性买断不参与自动续订`() {
        assertNull(AutoRenewal.plan(sub(cycle = BillingCycleType.ONE_TIME), today))
    }

    @Test
    fun `未到期不处理`() {
        assertNull(AutoRenewal.plan(sub(next = today.plusDays(1)), today))
    }

    @Test
    fun `已删除或无下次扣费日不处理`() {
        assertNull(AutoRenewal.plan(sub(deleted = true), today))
        assertNull(AutoRenewal.plan(sub(next = null), today))
    }

    @Test
    fun `年付到期推进一年`() {
        val yearly = sub(
            next = today.minusDays(3),
            cycle = BillingCycleType.YEARLY,
        )
        val out = AutoRenewal.plan(yearly, today)!!
        assertEquals(listOf(today.minusDays(3)), out.payments.map { it.paidAt })
        // 年付锚点是 1-26，10-23 之后的下一期是 2027-01-26。
        assertEquals(LocalDate.of(2027, 1, 26), out.subscription.nextChargeDate)
        assertNotNull(out.subscription.nextChargeDate)
    }
}
