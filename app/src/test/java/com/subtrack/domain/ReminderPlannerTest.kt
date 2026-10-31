package com.subtrack.domain

import com.subtrack.core.time.AppClock
import com.subtrack.domain.model.AppSettings
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.reminder.ReminderKind
import com.subtrack.reminder.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ReminderPlannerTest {

    private class FixedClock(private val today: LocalDate) : AppClock {
        override fun today(): LocalDate = today
        override fun nowMillis(): Long = 0L
        override fun reminderTime(): LocalTime = LocalTime.of(9, 0)
    }

    private val today = LocalDate.of(2026, 10, 26)

    private fun sub(
        next: LocalDate,
        offsets: List<Int> = listOf(3, 0),
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        muted: LocalDate? = null,
    ) = Subscription(
        id = "s1",
        name = "网易云音乐",
        categoryId = "music",
        cycle = BillingCycleType.MONTHLY,
        anchorDate = today.minusMonths(1),
        nextChargeDate = next,
        priceMinor = 1_500L,
        status = status,
        reminderOffsets = offsets,
        mutedUntil = muted,
    )

    @Test
    fun `plans advance and onset inside horizon`() {
        val plans = ReminderPlanner(FixedClock(today)).plan(sub(today.plusDays(10)))
        assertEquals(2, plans.size)
        assertTrue(plans.any { it.kind == ReminderKind.ONSET && it.offsetDays == 0 })
        assertTrue(plans.any { it.kind == ReminderKind.ADVANCE && it.offsetDays == 3 })
    }

    @Test
    fun `skips advance reminders already in the past`() {
        val plans = ReminderPlanner(FixedClock(today)).plan(sub(today.plusDays(1)))
        assertEquals(1, plans.size)
        assertEquals(ReminderKind.ONSET, plans.first().kind)
    }

    @Test
    fun `paused subscription has no reminders`() {
        val plans = ReminderPlanner(FixedClock(today))
            .plan(sub(today.plusDays(10), status = SubscriptionStatus.PAUSED))
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `muted window suppresses reminders`() {
        val plans = ReminderPlanner(FixedClock(today))
            .plan(sub(today.plusDays(10), muted = today.plusDays(20)))
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `过期 2 天时补发第 3 天和第 7 天`() {
        // 扣费日 10/24，今天 10/26 → 已过期 2 天；第 1 天(10/25)已错过，应排第 3 天(10/27)与第 7 天(10/31)
        val plans = ReminderPlanner(FixedClock(today)).plan(sub(today.minusDays(2)))
        val followUps = plans.filter { it.kind == ReminderKind.FOLLOW_UP }
        assertEquals(listOf(3, 7), followUps.map { it.offsetDays })
    }

    @Test
    fun `过期 0 天不补发`() {
        val plans = ReminderPlanner(FixedClock(today)).plan(sub(today))
        assertTrue(plans.none { it.kind == ReminderKind.FOLLOW_UP })
    }

    @Test
    fun `过期超过 7 天不再补发只留界面标记`() {
        val plans = ReminderPlanner(FixedClock(today)).plan(sub(today.minusDays(10)))
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `提醒时刻来自设置而非硬编码`() {
        val settings = AppSettings(reminderTime = LocalTime.of(21, 30))
        val plans = ReminderPlanner(FixedClock(today)).plan(sub(today.plusDays(1)), settings)
        val onset = plans.first { it.kind == ReminderKind.ONSET }
        val expected = today.plusDays(1).atTime(21, 30)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, onset.fireAtMillis)
    }

    @Test
    fun `自定义提前天数生效`() {
        val plans = ReminderPlanner(FixedClock(today))
            .plan(sub(today.plusDays(20), offsets = listOf(14, 0)))
        assertTrue(plans.any { it.kind == ReminderKind.ADVANCE && it.offsetDays == 14 })
        assertTrue(plans.any { it.kind == ReminderKind.ONSET })
    }

    @Test
    fun `onset fires at nine local time`() {
        val plans = ReminderPlanner(FixedClock(today)).plan(sub(today.plusDays(1)))
        val onset = plans.first { it.kind == ReminderKind.ONSET }
        val expected = today.plusDays(1).atTime(9, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, onset.fireAtMillis)
    }

    /**
     * 测试通知的哨兵 id 不能与任何真实订阅 id 冲突，也不能是个像真 id 的字符串。
     *
     * 这是真实踩过的坑：原来测试通知直接拿第一条订阅的 id 发，
     * 用户点通知上的"已续费"就会给那条订阅写一笔 chargeDate=1970 的假流水，
     * 并把下次扣费日推进到 1970 之后——测试动作污染了真实账本。
     */
    @Test
    fun `测试通知哨兵 id 必须与真实订阅隔离`() {
        val sentinel = com.subtrack.reminder.ReminderService.TEST_SUBSCRIPTION_ID
        // 不能为空（空了会在 get() 时命中意外数据）
        assertTrue(sentinel.isNotBlank())
        // 必须明显不是 UUID / seed / csv 这几种真实 id 形态
        assertTrue("哨兵不应是 UUID", !sentinel.matches(Regex("[0-9a-f-]{36}")))
        assertTrue("哨兵不应像 seed id", !sentinel.startsWith("seed-"))
        assertTrue("哨兵不应像 csv id", !sentinel.startsWith("csv-"))
    }
}
