package com.subtrack.domain

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.stats.Annualizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class AnnualizerTest {

    @Test
    fun `monthly annualizes twelve times`() {
        assertEquals(30_000L, Annualizer.annualizeMinor(2_500L, BillingCycleType.MONTHLY, null))
    }

    @Test
    fun `four weekly annualizes thirteen times not twelve`() {
        assertEquals(39_000L, Annualizer.annualizeMinor(3_000L, BillingCycleType.FOUR_WEEKLY, null))
    }

    @Test
    fun `biweekly annualizes twenty six times`() {
        assertEquals(78_000L, Annualizer.annualizeMinor(3_000L, BillingCycleType.BIWEEKLY, null))
    }

    @Test
    fun `semiannual annualizes twice`() {
        assertEquals(6_000L, Annualizer.annualizeMinor(3_000L, BillingCycleType.SEMIANNUAL, null))
    }

    @Test
    fun `custom thirty days annualizes 365 over n`() {
        assertEquals(36_500L, Annualizer.annualizeMinor(3_000L, BillingCycleType.CUSTOM_DAYS, 30))
    }

    @Test
    fun `one time is excluded`() {
        assertEquals(0L, Annualizer.annualizeMinor(9_999L, BillingCycleType.ONE_TIME, null))
    }

    @Test
    fun `monthly average of demo data is 7408`() {
        val annual = 19_900L + 2_500L * 12 + 3_000L * 13
        assertEquals(88_900L, annual)
        val monthly = Annualizer.monthlyAverageMinor(annual)
        assertEquals(7_408L, monthly)
        assertEquals(4L, abs(monthly * 12 - annual))
    }

    @Test
    fun `rounding error never exceeds six cents`() {
        for (annual in 100_000L..100_200L) {
            val monthly = Annualizer.monthlyAverageMinor(annual)
            assertTrue("annual=$annual", abs(monthly * 12 - annual) <= 6L)
        }
    }

    @Test
    fun `forecast excludes paused canceled expired and deleted`() {
        val today = LocalDate.of(2026, 10, 26)
        fun sub(status: SubscriptionStatus, deleted: Long? = null) = Subscription(
            id = status.name,
            name = status.name,
            categoryId = "video",
            cycle = BillingCycleType.MONTHLY,
            anchorDate = today,
            nextChargeDate = today,
            priceMinor = 1_000L,
            status = status,
            deletedAtUtcMillis = deleted,
        )
        val subs = listOf(
            sub(SubscriptionStatus.ACTIVE),
            sub(SubscriptionStatus.TRIAL),
            sub(SubscriptionStatus.PAUSED),
            sub(SubscriptionStatus.CANCELED),
            sub(SubscriptionStatus.EXPIRED),
            sub(SubscriptionStatus.ACTIVE, deleted = 1L),
        )
        assertEquals(24_000L, Annualizer.forecastAnnualMinor(subs))
        assertEquals(2_000L, Annualizer.forecastMonthlyMinor(subs))
    }
}
