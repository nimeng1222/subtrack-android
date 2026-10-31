package com.subtrack.network

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Job

class ContinuationEvidenceTest {
    private val url = "https://example.com/pricing"
    private fun question(material: String, period: String = "YEARLY", plan: String = "Pro") =
        PriceQuestion("Sample", "US", plan, "USD", period, "before tax", url, material)
    private val multi = "USD 20 per month or USD 200 per year"

    @Test fun mixed_monthly_amount_cannot_be_used_as_yearly() {
        val failure = runCatching { PriceEvidence.validateLegacy(question(multi), multi, 2000) }.exceptionOrNull()
        assertNotNull(failure)
    }
    @Test fun single_free_text_is_never_eligible_for_prefill() {
        val q = question("USD 200 per year")
        PriceEvidence.validateLegacy(q, q.material, 20000)
        assertFalse(PriceEvidence.structured(q, 20000, null))
    }
    @Test fun structured_quote_binds_all_dimensions() {
        val material = """{"quotes":[{"id":"m","service":"Sample","region":"US","plan":"Pro","currency":"USD","billingPeriod":"MONTHLY","conditions":"before tax","sourceUrl":"https://example.com/pricing","amountMinor":2000},{"id":"y","service":"Sample","region":"US","plan":"Pro","currency":"USD","billingPeriod":"YEARLY","conditions":"before tax","sourceUrl":"https://example.com/pricing","amountMinor":20000}]}"""
        assertTrue(PriceEvidence.structured(question(material), 20000, "y"))
        assertTrue(PriceEvidence.structured(question(material, "MONTHLY"), 2000, "m"))
        assertFalse(PriceEvidence.structured(question(material), 2000, "m"))
        assertFalse(PriceEvidence.structured(question(material, plan = "Basic"), 20000, "y"))
        assertFalse(PriceEvidence.structured(question(material).copy(conditions = "promotion"), 20000, "y"))
        assertFalse(PriceEvidence.structured(question(material).copy(region = "EU"), 20000, "y"))
    }
    @Test fun revoked_lease_cancels_work_and_rejects_late_completion() {
        val lease = RequestLease(); val ticket = lease.ticket(); val job = Job()
        lease.attach(job); lease.invalidate()
        assertTrue(job.isCancelled); assertFalse(lease.valid(ticket)); assertTrue(lease.valid(lease.ticket()))
        lease.close(); assertFalse(lease.valid(ticket))
    }
}
