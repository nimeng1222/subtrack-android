package com.subtrack.domain

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.state.SubscriptionActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SubscriptionActionsTest {

    private val today = LocalDate.of(2026, 10, 26)
    private val anchor = LocalDate.of(2026, 1, 31)

    private fun sub(
        next: LocalDate? = LocalDate.of(2026, 10, 31),
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        cycle: BillingCycleType = BillingCycleType.MONTHLY,
        trialEnd: LocalDate? = null,
    ) = Subscription(
        id = "s1",
        name = "网易云音乐",
        categoryId = "music",
        cycle = cycle,
        anchorDate = anchor,
        nextChargeDate = next,
        priceMinor = 1_500L,
        status = status,
        trialEndDate = trialEnd,
    )

    @Test
    fun `正常续费写一笔流水并推进一期`() {
        val out = SubscriptionActions.confirmRenewal(sub(next = today.minusDays(1)), today)
        assertEquals(1, out.payments.size)
        assertEquals(today.minusDays(1), out.payments.first().paidAt)
        assertEquals(1_500L, out.payments.first().amountMinor)
        assertTrue(out.subscription.nextChargeDate!!.isAfter(today))
        assertNull(out.toast)
    }

    @Test
    fun `补记多期时每期都写流水并提示已推进N期`() {
        val out = SubscriptionActions.confirmRenewal(
            sub(next = LocalDate.of(2026, 7, 31)),
            today,
        )
        assertEquals(3, out.payments.size)
        assertEquals("已推进 3 期", out.toast)
        assertEquals(LocalDate.of(2026, 10, 31), out.subscription.nextChargeDate)
    }

    @Test
    fun `一次性买断确认后转过期不再滚动`() {
        val out = SubscriptionActions.confirmRenewal(
            sub(next = LocalDate.of(2026, 9, 1), cycle = BillingCycleType.ONE_TIME),
            today,
        )
        assertNull(out.subscription.nextChargeDate)
        assertEquals(SubscriptionStatus.EXPIRED, out.subscription.status)
        assertEquals(1, out.payments.size)
    }

    @Test
    fun `取消会记下取消日期`() {
        val out = SubscriptionActions.cancel(sub(), today)
        assertEquals(SubscriptionStatus.CANCELED, out.subscription.status)
        assertEquals(today, out.subscription.canceledAt)
    }

    @Test
    fun `暂停不清空下次扣费日`() {
        val out = SubscriptionActions.pause(sub())
        assertEquals(SubscriptionStatus.PAUSED, out.subscription.status)
        assertNotNull(out.subscription.nextChargeDate)
    }

    @Test
    fun `恢复需要传入选定日期不擅自猜测`() {
        val chosen = LocalDate.of(2026, 11, 15)
        val out = SubscriptionActions.resume(sub(status = SubscriptionStatus.PAUSED), chosen)
        assertEquals(SubscriptionStatus.ACTIVE, out.subscription.status)
        assertEquals(chosen, out.subscription.nextChargeDate)
        assertNull(out.subscription.canceledAt)
    }

    @Test
    fun `恢复建议日期不早于今天`() {
        val suggestion = SubscriptionActions.suggestedResumeDate(
            sub(next = LocalDate.of(2026, 6, 30), status = SubscriptionStatus.PAUSED),
            today,
        )
        assertTrue(!suggestion.isBefore(today))
    }

    @Test
    fun `试用转正写流水并清空试用结束日`() {
        val out = SubscriptionActions.convertTrial(
            sub(status = SubscriptionStatus.TRIAL, trialEnd = today, next = today),
            today,
        )
        assertEquals(SubscriptionStatus.ACTIVE, out.subscription.status)
        assertNull(out.subscription.trialEndDate)
        assertEquals(1, out.payments.size)
        assertEquals(today, out.payments.first().paidAt)
    }

    @Test
    fun `标记过期与取消等价`() {
        val expired = SubscriptionActions.expire(sub(), today).subscription
        assertEquals(SubscriptionStatus.EXPIRED, expired.status)
        assertTrue(!expired.isForecastIncluded)
    }

    @Test
    fun `静音本期不等于取消`() {
        val out = SubscriptionActions.muteThisCycle(sub(), today)
        assertEquals(SubscriptionStatus.ACTIVE, out.subscription.status)
        // mutedUntil 语义 = 本期扣费日（不是扣费日+1）：规划器据此把该期全部补发一并静音
        assertEquals(today, out.subscription.mutedUntil)
    }

    @Test
    fun `明天再说会请求创建明日计划`() {
        // v1.3.2 的 snooze 只写 mutedUntil：逾期超过 7 天时规划器不再产出节点，
        // 用户说"明天再提醒"却没有明日任务。现在 Outcome 必须携带 snoozeRequested，
        // 由统一落库入口创建可恢复、可撤销的持久明日计划。
        val out = SubscriptionActions.snooze(sub(), today)
        assertTrue(out.snoozeRequested)
        // Snooze and explicit period mute are distinct: the persisted SNOOZE row now carries deferral.
        assertEquals(null, out.subscription.mutedUntil)
    }

    @Test
    fun `明天再说不动到期日与状态`() {
        val before = sub()
        val out = SubscriptionActions.snooze(before, today)
        assertEquals(before.nextChargeDate, out.subscription.nextChargeDate)
        assertEquals(before.status, out.subscription.status)
    }

    @Test
    fun `撤销删除清掉软删除标记`() {
        val deleted = sub().copy(deletedAtUtcMillis = 123L)
        val out = SubscriptionActions.restore(deleted)
        assertNull(out.subscription.deletedAtUtcMillis)
    }
}
