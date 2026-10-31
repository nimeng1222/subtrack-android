package com.subtrack.domain

import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Subscription
import com.subtrack.domain.model.SubscriptionStatus
import com.subtrack.domain.state.DisplayStateResolver
import com.subtrack.domain.state.SubscriptionDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DisplayStateResolverTest {

    private val today = LocalDate.of(2026, 10, 26)

    private fun sub(
        next: LocalDate?,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ) = Subscription(
        id = "s",
        name = "n",
        categoryId = "video",
        cycle = BillingCycleType.MONTHLY,
        anchorDate = today.minusMonths(1),
        nextChargeDate = next,
        priceMinor = 1000L,
        status = status,
    )

    @Test
    fun `overdue active becomes pending and never expires`() {
        val state = DisplayStateResolver.resolve(sub(today.minusDays(40)), today, hasPaymentOnDueDate = false)
        assertTrue(state is SubscriptionDisplayState.PendingConfirm)
        assertEquals(40, (state as SubscriptionDisplayState.PendingConfirm).overdueDays)
    }

    @Test
    fun `payment on due date clears pending`() {
        val state = DisplayStateResolver.resolve(sub(today.minusDays(2)), today, hasPaymentOnDueDate = true)
        assertEquals(SubscriptionDisplayState.Active, state)
    }

    @Test
    fun `paused stays paused even when overdue`() {
        val state = DisplayStateResolver.resolve(
            sub(today.minusDays(5), SubscriptionStatus.PAUSED), today, hasPaymentOnDueDate = false,
        )
        assertEquals(SubscriptionDisplayState.Paused, state)
    }

    @Test
    fun `canceled stays canceled`() {
        val state = DisplayStateResolver.resolve(
            sub(null, SubscriptionStatus.CANCELED), today, hasPaymentOnDueDate = false,
        )
        assertEquals(SubscriptionDisplayState.Canceled, state)
    }

    @Test
    fun `future charge stays active`() {
        val state = DisplayStateResolver.resolve(sub(today.plusDays(3)), today, hasPaymentOnDueDate = false)
        assertEquals(SubscriptionDisplayState.Active, state)
    }
}
