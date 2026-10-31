package com.subtrack.domain

import com.subtrack.domain.cycle.RenewalEngine
import com.subtrack.domain.model.BillingCycleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RenewalEngineTest {

    private val anchor = LocalDate.of(2026, 1, 31)

    @Test
    fun `正常续费推进一期`() {
        val r = RenewalEngine.confirm(
            anchor = anchor,
            type = BillingCycleType.MONTHLY,
            customDays = null,
            fromChargeDate = LocalDate.of(2026, 10, 31),
            today = LocalDate.of(2026, 10, 31),
        )
        assertEquals(LocalDate.of(2026, 11, 30), r.nextChargeDate)
        assertEquals(1, r.periodsAdvanced)
        assertEquals(listOf(LocalDate.of(2026, 10, 31)), r.credits)
        assertFalse(r.advancedMultiple)
    }

    @Test
    fun `缺省多期时反复推进到第一个不早于今天`() {
        // 用户 3 个月没打开 App，从 7 月这期补记
        val r = RenewalEngine.confirm(
            anchor = anchor,
            type = BillingCycleType.MONTHLY,
            customDays = null,
            fromChargeDate = LocalDate.of(2026, 7, 31),
            today = LocalDate.of(2026, 10, 26),
        )
        // 今天 10/26：7/31、8/31、9/30 三笔已经发生，要补记；10/31 还没到，是下一次扣费日
        assertEquals(
            listOf(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30)),
            r.credits,
        )
        assertEquals(3, r.periodsAdvanced)
        assertTrue(r.advancedMultiple)
        assertEquals(LocalDate.of(2026, 10, 31), r.nextChargeDate)
    }

    @Test
    fun `月末锚点不漂移 31 号在短月钳制到月末`() {
        var date: LocalDate? = LocalDate.of(2026, 1, 31)
        val seen = mutableListOf<LocalDate>()
        repeat(6) {
            date = com.subtrack.domain.cycle.CycleEngine.nextOccurrenceAfter(
                anchor, BillingCycleType.MONTHLY, null, date!!,
            )
            if (date != null) seen += date!!
        }
        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31),
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 7, 31),
            ),
            seen,
        )
    }

    @Test
    fun `一次性订阅不滚动且不重复记账`() {
        val r = RenewalEngine.confirm(
            anchor = LocalDate.of(2026, 9, 1),
            type = BillingCycleType.ONE_TIME,
            customDays = null,
            fromChargeDate = LocalDate.of(2026, 9, 1),
            today = LocalDate.of(2026, 10, 26),
        )
        assertNull(r.nextChargeDate)
        assertEquals(0, r.periodsAdvanced)
        assertEquals(listOf(LocalDate.of(2026, 9, 1)), r.credits)
    }

    @Test
    fun `周付补记按 7 天步进`() {
        val r = RenewalEngine.confirm(
            anchor = LocalDate.of(2026, 9, 1),
            type = BillingCycleType.WEEKLY,
            customDays = null,
            fromChargeDate = LocalDate.of(2026, 10, 6),
            today = LocalDate.of(2026, 10, 26),
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 20)), r.credits)
        assertEquals(LocalDate.of(2026, 10, 27), r.nextChargeDate)
    }

    @Test
    fun `未来期次不补记`() {
        val r = RenewalEngine.confirm(
            anchor = anchor,
            type = BillingCycleType.MONTHLY,
            customDays = null,
            fromChargeDate = LocalDate.of(2026, 11, 30),
            today = LocalDate.of(2026, 10, 26),
        )
        assertEquals(1, r.periodsAdvanced)
        assertEquals(LocalDate.of(2026, 12, 31), r.nextChargeDate)
    }

    @Test
    fun `自定义天数补记`() {
        val r = RenewalEngine.confirm(
            anchor = LocalDate.of(2026, 1, 1),
            type = BillingCycleType.CUSTOM_DAYS,
            customDays = 45,
            fromChargeDate = LocalDate.of(2026, 1, 1),
            today = LocalDate.of(2026, 10, 26),
        )
        // 45 天步进：1/1, 2/15, 4/1, 5/16, 6/30, 8/14, 9/28 … 逐个补记到 >= 10/26
        assertTrue(r.periodsAdvanced >= 6)
        assertTrue(r.nextChargeDate!!.isAfter(LocalDate.of(2026, 10, 26)) || r.nextChargeDate == LocalDate.of(2026, 10, 26))
    }

    @Test
    fun `只推进不记账用于跳过`() {
        val next = RenewalEngine.advanceTo(
            anchor = anchor,
            type = BillingCycleType.MONTHLY,
            customDays = null,
            from = LocalDate.of(2026, 7, 31),
            today = LocalDate.of(2026, 10, 26),
        )
        // 今天 10/26，10/31 还没到，所以停在 10/31
        assertEquals(LocalDate.of(2026, 10, 31), next)
    }

    @Test
    fun `补记上限保护脏数据`() {
        // 锚点 1970，周付，确保不会无限循环
        val r = RenewalEngine.confirm(
            anchor = LocalDate.of(1970, 1, 1),
            type = BillingCycleType.WEEKLY,
            customDays = null,
            fromChargeDate = LocalDate.of(1970, 1, 1),
            today = LocalDate.of(2026, 10, 26),
        )
        assertTrue(r.periodsAdvanced <= RenewalEngine.MAX_CATCH_UP + 1)
    }
}
