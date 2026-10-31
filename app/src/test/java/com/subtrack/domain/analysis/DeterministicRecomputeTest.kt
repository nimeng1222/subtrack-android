package com.subtrack.domain.analysis

import org.junit.Assert.*
import org.junit.Test

class DeterministicRecomputeTest {
    private fun quote(
        amount: Long,
        period: PricePeriod,
        currency: String = "USD",
        service: String = "Example",
        plan: String = "Pro",
        tax: String = "before tax",
        discount: String = "none",
        benefit: String = "same",
        newUser: Boolean = false,
        verification: VerificationStatus = VerificationStatus.VERIFIED_OFFICIAL,
    ) = StructuredPriceQuote(
        quoteId = "q-$amount-$period", service = service, plan = plan, region = "US", currency = currency,
        amountMinor = amount, period = period, taxTerms = tax, discountTerms = discount, benefitTerms = benefit,
        newUserOnly = newUser, sourceUrl = "https://shop.example.com/plan", sourceType = SourceType.OFFICIAL,
        pageDate = "2026-10-20", retrievedAtMillis = 1_760_000_000_000L, evidenceText = "evidence",
        verification = verification,
    )

    @Test fun annualizes_and_differences_exactly() {
        assertEquals(24_000L, DeterministicRecompute.annualMinor(2_000, PricePeriod.MONTHLY))
        assertEquals(20_000L, DeterministicRecompute.annualMinor(20_000, PricePeriod.YEARLY))
        assertEquals(4_000L, DeterministicRecompute.annualDifferenceMinor(2_000, PricePeriod.MONTHLY, 20_000, PricePeriod.YEARLY))
    }

    @Test fun wrong_model_arithmetic_is_recomputed_correctly() {
        val result = DeterministicRecompute.compare(
            quote(2_000, PricePeriod.MONTHLY), quote(20_000, PricePeriod.YEARLY))
        assertTrue(result.comparable)
        assertEquals(24_000L, result.firstAnnualMinor!!)
        assertEquals(20_000L, result.secondAnnualMinor!!)
        assertEquals(4_000L, result.annualDifferenceMinor!!)
    }

    @Test fun currency_mismatch_is_not_comparable() {
        val result = DeterministicRecompute.compare(quote(2_000, PricePeriod.MONTHLY), quote(20_000, PricePeriod.YEARLY, currency = "CNY"))
        assertFalse(result.comparable)
        assertNull(result.annualDifferenceMinor)
        assertTrue(result.reason.contains("币种"))
    }

    @Test fun tax_discount_benefit_newuser_mismatch_is_not_comparable() {
        for (other in listOf(
            quote(20_000, PricePeriod.YEARLY, tax = "included"),
            quote(20_000, PricePeriod.YEARLY, discount = "20% off"),
            quote(20_000, PricePeriod.YEARLY, benefit = "extra device"),
            quote(20_000, PricePeriod.YEARLY, newUser = true),
        )) {
            assertFalse(DeterministicRecompute.compare(quote(2_000, PricePeriod.MONTHLY), other).comparable)
        }
    }

    @Test fun expired_or_conflict_quotes_refuse_conclusion() {
        assertFalse(DeterministicRecompute.compare(
            quote(2_000, PricePeriod.MONTHLY), quote(20_000, PricePeriod.YEARLY, verification = VerificationStatus.EXPIRED)).comparable)
        assertFalse(DeterministicRecompute.compare(
            quote(2_000, PricePeriod.MONTHLY), quote(20_000, PricePeriod.YEARLY, verification = VerificationStatus.CONFLICT)).comparable)
    }

    @Test fun fx_conversion_is_integer_exact() {
        // JPY 10,000 (1,000,000 minor) × 0.042202 CNY => 422.02 CNY = 42,202 minor
        assertEquals(42_202L, DeterministicRecompute.convertToCnyMinor(1_000_000, 42_202_000))
        assertEquals(0L, DeterministicRecompute.convertToCnyMinor(0, 42_202_000))
        assertTrue(runCatching { DeterministicRecompute.convertToCnyMinor(100, 0) }.isFailure)
    }

    @Test fun savings_is_a_forward_projection_not_realized() {
        val simulation = DeterministicRecompute.savings(listOf(
            quote(2_000, PricePeriod.MONTHLY), quote(20_000, PricePeriod.YEARLY)))
        assertTrue(simulation.comparable)
        assertEquals(44_000L, simulation.estimatedAnnualAvoidedMinor!!)
        assertTrue(simulation.note.contains("并非已经节省"))
    }

    @Test fun savings_refuses_mixed_currency() {
        val simulation = DeterministicRecompute.savings(listOf(
            quote(2_000, PricePeriod.MONTHLY), quote(20_000, PricePeriod.YEARLY, currency = "CNY")))
        assertFalse(simulation.comparable)
        assertNull(simulation.estimatedAnnualAvoidedMinor)
    }

    @Test fun out_of_range_amounts_are_rejected_not_wrapped() {
        assertTrue(runCatching {
            DeterministicRecompute.annualDifferenceMinor(Long.MAX_VALUE / 2, PricePeriod.MONTHLY, 0, PricePeriod.MONTHLY)
        }.isFailure)
    }
}
