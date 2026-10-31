package com.subtrack.domain

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.stats.BillingCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BillingCalendarTest {

    private val year = 2026

    private fun sub(
        id: String,
        price: Long,
        cycle: BillingCycleType = BillingCycleType.MONTHLY,
        anchor: LocalDate = LocalDate.of(2026, 1, 15),
        next: LocalDate? = null,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        customDays: Int? = null,
    ) = Subscription(
        id = id,
        name = id,
        categoryId = "video",
        cycle = cycle,
        customCycleDays = customDays,
        anchorDate = anchor,
        nextChargeDate = next ?: anchor,
        priceMinor = price,
        status = status,
    )

    @Test
    fun `月付订阅一年内出现 12 次`() {
        val cal = BillingCalendar.year(listOf(sub("a", 10_00)), year)
        assertEquals(12, cal.size)
        assertTrue(cal.keys.all { it.year == year })
    }

    @Test
    fun `季付一年内出现 4 次`() {
        val cal = BillingCalendar.year(listOf(sub("a", 10_00, BillingCycleType.QUARTERLY)), year)
        assertEquals(4, cal.size)
    }

    @Test
    fun `年付一年内只出现 1 次`() {
        val cal = BillingCalendar.year(listOf(sub("a", 199_00, BillingCycleType.YEARLY)), year)
        assertEquals(1, cal.size)
        assertEquals(199_00L, cal.values.first().totalMinor)
    }

    @Test
    fun `月末锚点在小月钳制到月末`() {
        val cal = BillingCalendar.year(
            listOf(sub("a", 10_00, anchor = LocalDate.of(2026, 1, 31))),
            year,
        )
        val days = cal.keys.sorted()
        // 1/31, 2/28, 3/31, 4/30, 5/31, 6/30, 7/31 ...
        assertEquals(LocalDate.of(2026, 2, 28), days[1])
        assertEquals(LocalDate.of(2026, 4, 30), days[3])
        assertEquals(LocalDate.of(2026, 6, 30), days[5])
    }

    @Test
    fun `同一天多笔会合并到一个桶`() {
        val cal = BillingCalendar.year(
            listOf(sub("a", 10_00), sub("b", 20_00)),
            year,
        )
        val jan15 = LocalDate.of(2026, 1, 15)
        assertEquals(2, cal[jan15]!!.count)
        assertEquals(30_00L, cal[jan15]!!.totalMinor)
    }

    @Test
    fun `暂停与取消不进日历`() {
        val cal = BillingCalendar.year(
            listOf(
                sub("active", 10_00),
                sub("paused", 20_00, status = SubscriptionStatus.PAUSED),
                sub("canceled", 30_00, status = SubscriptionStatus.CANCELED),
            ),
            year,
        )
        val all = cal.values.flatMap { it.subscriptions }.map { it.id }.toSet()
        assertEquals(setOf("active"), all)
    }

    @Test
    fun `一次性只出现在买断当天`() {
        val cal = BillingCalendar.year(
            listOf(
                sub(
                    "onetime", 599_00, BillingCycleType.ONE_TIME,
                    anchor = LocalDate.of(2026, 3, 8), next = LocalDate.of(2026, 3, 8),
                ),
            ),
            year,
        )
        assertEquals(1, cal.size)
        assertEquals(LocalDate.of(2026, 3, 8), cal.keys.first())
    }

    @Test
    fun `周付一年出现约 52 次`() {
        val cal = BillingCalendar.year(
            listOf(sub("a", 3_00, BillingCycleType.WEEKLY, anchor = LocalDate.of(2026, 1, 5))),
            year,
        )
        assertTrue("实际 ${cal.size}", cal.size in 52..53)
    }

    @Test
    fun `自定义天数 45 天一年约 8 次`() {
        val cal = BillingCalendar.year(
            listOf(
                sub(
                    "a", 10_00, BillingCycleType.CUSTOM_DAYS,
                    anchor = LocalDate.of(2026, 1, 1), customDays = 45,
                ),
            ),
            year,
        )
        assertTrue("实际 ${cal.size}", cal.size in 8..9)
    }

    @Test
    fun `月度总额是 12 个元素且合计等于全年`() {
        val subs = listOf(sub("a", 10_00), sub("b", 199_00, BillingCycleType.YEARLY))
        val monthly = BillingCalendar.monthlyTotals(subs, year)
        assertEquals(12, monthly.size)
        val yearTotal = BillingCalendar.year(subs, year).values.sumOf { it.totalMinor }
        assertEquals(yearTotal, monthly.sum())
    }

    @Test
    fun `近 6 个月升序且包含当月`() {
        val today = LocalDate.of(2026, 10, 26)
        val months = BillingCalendar.recentMonths(listOf(sub("a", 10_00)), today, 6)
        assertEquals(6, months.size)
        assertEquals(YearMonth.of(2026, 5), months.first().yearMonth)
        assertEquals(YearMonth.of(2026, 10), months.last().yearMonth)
        // 每个月的 15 号都有扣费
        assertTrue(months.all { it.totalMinor == 10_00L })
    }

    @Test
    fun `热力等级按相对比例分档`() {
        assertEquals(0, BillingCalendar.heatLevel(0, 1000))
        assertEquals(0, BillingCalendar.heatLevel(100, 0))
        assertEquals(1, BillingCalendar.heatLevel(100, 1000))
        assertEquals(2, BillingCalendar.heatLevel(300, 1000))
        assertEquals(3, BillingCalendar.heatLevel(600, 1000))
        assertEquals(4, BillingCalendar.heatLevel(900, 1000))
    }

    @Test
    fun `历史月份能算出已发生的扣费`() {
        // 这是真实 seed 数据的形状：9/27 建仓，下次扣费已经推到 10/28。
        // 9 月那笔必须出现在日历里，否则趋势图会把历史月份全显示成 0。
        val sub = sub(
            "a", 15_00,
            anchor = LocalDate.of(2026, 9, 27),
            next = LocalDate.of(2026, 10, 27),
        )
        // 游标是 10/27；9 月窗口里应该只有回溯出来的 9/27 这一天
        val sept = BillingCalendar.month(listOf(sub), YearMonth.of(2026, 9))
        assertEquals(1, sept.size)
        assertEquals(LocalDate.of(2026, 9, 27), sept.keys.first())

        // 近 6 个月里 9 月与 10 月都该有值
        val months = BillingCalendar.recentMonths(listOf(sub), LocalDate.of(2026, 10, 27), 6)
        val septTotal = months.first { it.yearMonth == YearMonth.of(2026, 9) }.totalMinor
        val octTotal = months.first { it.yearMonth == YearMonth.of(2026, 10) }.totalMinor
        assertEquals(15_00L, septTotal)
        assertEquals(15_00L, octTotal)
    }

    @Test
    fun `空订阅列表得到空日历`() {
        assertTrue(BillingCalendar.year(emptyList(), year).isEmpty())
        assertEquals(List(12) { 0L }, BillingCalendar.monthlyTotals(emptyList(), year))
    }

    @Test
    fun `订阅创建前的日子不被算进日历`() {
        // 锚点在 6 月，日历不应该显示 1-5 月有扣费
        val cal = BillingCalendar.year(
            listOf(sub("a", 10_00, anchor = LocalDate.of(2026, 6, 10), next = LocalDate.of(2026, 6, 10))),
            year,
        )
        assertTrue(cal.keys.all { it.monthValue >= 6 })
    }

    @Test
    fun `锚点之前的月份不能凭空出现扣费`() {
        // anchor 与 nextChargeDate 保持同一"26 号"模式（正常数据就是这样）。
        // 5-8 月订阅还不存在，必须全是 0。
        val sub = sub(
            "a", 15_00,
            anchor = LocalDate.of(2026, 9, 26),
            next = LocalDate.of(2026, 10, 26),
        )
        val months = BillingCalendar.recentMonths(listOf(sub), LocalDate.of(2026, 10, 27), 6)
        val may = months.first { it.yearMonth == YearMonth.of(2026, 5) }
        val aug = months.first { it.yearMonth == YearMonth.of(2026, 8) }
        assertEquals("锚点前不应有扣费", 0L, may.totalMinor)
        assertEquals("锚点前不应有扣费", 0L, aug.totalMinor)
        // 9 月与 10 月各有一笔
        assertEquals(15_00L, months.first { it.yearMonth == YearMonth.of(2026, 9) }.totalMinor)
        assertEquals(15_00L, months.first { it.yearMonth == YearMonth.of(2026, 10) }.totalMinor)
    }

}
