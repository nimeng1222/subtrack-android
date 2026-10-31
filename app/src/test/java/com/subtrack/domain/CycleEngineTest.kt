package com.subtrack.domain

import com.subtrack.domain.cycle.CycleEngine
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.BillingCycleType.BIWEEKLY
import com.subtrack.domain.model.BillingCycleType.CUSTOM_DAYS
import com.subtrack.domain.model.BillingCycleType.FOUR_WEEKLY
import com.subtrack.domain.model.BillingCycleType.MONTHLY
import com.subtrack.domain.model.BillingCycleType.ONE_TIME
import com.subtrack.domain.model.BillingCycleType.QUARTERLY
import com.subtrack.domain.model.BillingCycleType.SEMIANNUAL
import com.subtrack.domain.model.BillingCycleType.WEEKLY
import com.subtrack.domain.model.BillingCycleType.YEARLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CycleEngineTest {

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    /** 已续费后“下一期”的语义：严格晚于 from。文档 21 条边界表用的是这个。 */
    private fun nextAfter(anchor: String, type: BillingCycleType, from: String, custom: Int? = null): LocalDate? =
        CycleEngine.nextOccurrenceAfter(d(anchor), type, custom, d(from))

    /** 规划窗口的语义：含 from 当天。 */
    private fun atOrAfter(anchor: String, type: BillingCycleType, from: String, custom: Int? = null): LocalDate? =
        CycleEngine.occurrenceAtOrAfter(d(anchor), type, custom, d(from))

    @Test
    fun `monthly anchor 31 clamps to February`() {
        assertEquals(d("2026-02-28"), nextAfter("2026-01-31", MONTHLY, "2026-01-31"))
    }

    @Test
    fun `monthly anchor 31 returns to 31 after short month`() {
        assertEquals(d("2026-03-31"), nextAfter("2026-01-31", MONTHLY, "2026-02-28"))
    }

    @Test
    fun `monthly anchor 31 survives two short months`() {
        assertEquals(d("2026-05-31"), nextAfter("2026-01-31", MONTHLY, "2026-04-30"))
    }

    @Test
    fun `monthly anchor 30 returns to 30 after February`() {
        assertEquals(d("2026-03-30"), nextAfter("2026-01-30", MONTHLY, "2026-02-28"))
    }

    @Test
    fun `yearly leap day clamps in non leap year`() {
        assertEquals(d("2025-02-28"), nextAfter("2024-02-29", YEARLY, "2024-02-29"))
    }

    @Test
    fun `yearly leap day returns in leap year`() {
        assertEquals(d("2028-02-29"), nextAfter("2024-02-29", YEARLY, "2027-02-28"))
    }

    @Test
    fun `quarterly crossing year and short month`() {
        assertEquals(d("2027-02-28"), nextAfter("2026-11-30", QUARTERLY, "2026-11-30"))
    }

    @Test
    fun `quarterly keeps 31st`() {
        assertEquals(d("2026-08-31"), nextAfter("2026-05-31", QUARTERLY, "2026-05-31"))
    }

    @Test
    fun `semiannual from May 31`() {
        assertEquals(d("2026-11-30"), nextAfter("2026-05-31", SEMIANNUAL, "2026-05-31"))
    }

    @Test
    fun `weekly crosses year boundary`() {
        assertEquals(d("2027-01-04"), nextAfter("2026-12-28", WEEKLY, "2026-12-28"))
    }

    @Test
    fun `biweekly adds 14 days`() {
        assertEquals(d("2027-01-11"), nextAfter("2026-12-28", BIWEEKLY, "2026-12-28"))
    }

    @Test
    fun `four weekly is 28 days not one month`() {
        assertEquals(d("2026-02-02"), nextAfter("2026-01-05", FOUR_WEEKLY, "2026-01-05"))
    }

    @Test
    fun `custom 45 days`() {
        assertEquals(d("2026-02-15"), nextAfter("2026-01-01", CUSTOM_DAYS, "2026-01-01", 45))
    }

    @Test
    fun `custom 365 days is one year`() {
        assertEquals(d("2027-01-01"), nextAfter("2026-01-01", CUSTOM_DAYS, "2026-01-01", 365))
    }

    @Test
    fun `at or after includes the anchor itself`() {
        assertEquals(d("2026-03-15"), atOrAfter("2026-03-15", MONTHLY, "2026-03-15"))
    }

    @Test
    fun `at or after with earlier from returns anchor`() {
        assertEquals(d("2026-03-15"), atOrAfter("2026-03-15", MONTHLY, "2026-01-01"))
    }

    @Test
    fun `one time in the past returns null`() {
        assertNull(atOrAfter("2026-03-15", ONE_TIME, "2026-04-01"))
    }

    @Test
    fun `one time in the future returns anchor`() {
        assertEquals(d("2026-03-15"), atOrAfter("2026-03-15", ONE_TIME, "2026-01-01"))
    }

    @Test
    fun `occurrences between 90 days for weekly`() {
        val list = CycleEngine.occurrencesBetween(d("2026-01-01"), WEEKLY, null, d("2026-01-01"), d("2026-03-31"))
        assertEquals(13, list.size)
    }

    @Test
    fun `plus periods keeps month end anchor`() {
        assertEquals(d("2026-02-28"), CycleEngine.plusPeriods(d("2026-01-31"), MONTHLY, null, 1))
        assertEquals(d("2026-03-31"), CycleEngine.plusPeriods(d("2026-01-31"), MONTHLY, null, 2))
        assertEquals(d("2027-01-31"), CycleEngine.plusPeriods(d("2026-01-31"), MONTHLY, null, 12))
    }

    @Test
    fun `three years of monthly never drifts`() {
        val anchor = d("2026-01-31")
        var cursor = anchor
        repeat(36) {
            val nxt = CycleEngine.nextOccurrenceAfter(anchor, MONTHLY, null, cursor)!!
            val expectedDay = minOf(31, nxt.lengthOfMonth())
            assertEquals("drift at $nxt", expectedDay, nxt.dayOfMonth)
            cursor = nxt
        }
    }

    @Test
    fun `custom days requires value`() {
        try {
            nextAfter("2026-01-01", CUSTOM_DAYS, "2026-01-01")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    // ── 向前回溯（账单日历用）──
    @Test
    fun `previousOccurrenceBefore 返回严格早于给定日的最近一期`() {
        val anchor = LocalDate.of(2026, 1, 15)
        assertEquals(
            LocalDate.of(2026, 9, 15),
            CycleEngine.previousOccurrenceBefore(anchor, BillingCycleType.MONTHLY, null, LocalDate.of(2026, 10, 15)),
        )
        // 给的日子本身是扣费日，也要返回上一期（严格早于）
        assertEquals(
            LocalDate.of(2026, 9, 15),
            CycleEngine.previousOccurrenceBefore(anchor, BillingCycleType.MONTHLY, null, LocalDate.of(2026, 10, 15)),
        )
    }

    @Test
    fun `previousOccurrenceBefore 月末锚点不漂移`() {
        val anchor = LocalDate.of(2026, 1, 31)
        assertEquals(
            LocalDate.of(2026, 2, 28),
            CycleEngine.previousOccurrenceBefore(anchor, BillingCycleType.MONTHLY, null, LocalDate.of(2026, 3, 31)),
        )
    }

    @Test
    fun `previousOccurrenceBefore 早于锚点返回 null`() {
        val anchor = LocalDate.of(2026, 6, 1)
        assertNull(CycleEngine.previousOccurrenceBefore(anchor, BillingCycleType.MONTHLY, null, LocalDate.of(2026, 5, 1)))
        assertNull(CycleEngine.previousOccurrenceBefore(anchor, BillingCycleType.MONTHLY, null, anchor))
    }

    @Test
    fun `previousOccurrenceBefore 一次性返回 null`() {
        assertNull(
            CycleEngine.previousOccurrenceBefore(
                LocalDate.of(2026, 1, 1), BillingCycleType.ONE_TIME, null, LocalDate.of(2026, 6, 1),
            ),
        )
    }
}
