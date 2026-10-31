package com.subtrack.network

import com.subtrack.domain.analysis.*
import com.subtrack.network.search.WikipediaSearch
import org.junit.Assert.*
import org.junit.Test

class SearchAnalysisTest {
    private fun quote(period: PricePeriod, price: Long) = ComparableQuote("Example", "Plan", "US", "USD",
        price, period, "before tax", "no offer", "same entitlement", "https://example.com")

    @Test fun query_cannot_redirect_search_to_internal_host() {
        val url = WikipediaSearch.requestUrl("http://127.0.0.1/admin?key=secret")
        assertTrue(url.startsWith("https://en.wikipedia.org/w/api.php?"))
        assertFalse(url.contains("http://127.0.0.1"))
        assertTrue(runCatching { WikipediaSearch.requestUrl("x\ny") }.isFailure)
    }

    @Test fun search_snippets_are_plain_text_and_not_price_verification() {
        val response = """{"query":{"search":[{"pageid":42,"title":"Example","snippet":"A <span class=\"searchmatch\">plan</span> &amp; text","timestamp":"2026-10-28T01:02:03Z"}]}}"""
        val hit = WikipediaSearch.decode(response, 123).single()
        assertEquals("https://en.wikipedia.org/?curid=42", hit.url)
        assertEquals("A plan & text", hit.snippet)
        assertEquals("THIRD_PARTY_SEARCH_SUMMARY", hit.sourceType)
        assertEquals("未核验价格", hit.verification)
        assertEquals(123L, hit.retrievedAtMillis)
    }

    @Test fun malformed_search_or_server_error_is_not_empty_success() {
        assertTrue(runCatching { WikipediaSearch.decode("""{"error":{"code":"ratelimited"}}""", 0) }.isFailure)
        assertTrue(runCatching { WikipediaSearch.decode("""{"query":{"search":[{"pageid":-1,"title":"x"}]}}""", 0) }.isFailure)
        assertTrue(runCatching { WikipediaSearch.decode("x".repeat(131073), 0) }.isFailure)
    }

    @Test fun compare_recomputes_yearly_amount_and_refuses_mismatches() {
        val a = quote(PricePeriod.MONTHLY, 2000)
        val b = quote(PricePeriod.YEARLY, 20000)
        val result = PriceAnalysis.compare(a, b)
        assertEquals(24000, result.firstAnnualMinor)
        assertEquals(4000, result.annualDifferenceMinor)
        for (other in listOf(b.copy(currency = "CNY"), b.copy(region = "JP"), b.copy(taxTerms = "included"),
            b.copy(entitlement = "different"), b.copy(offerTerms = ""))) {
            assertTrue(runCatching { PriceAnalysis.compare(a, other) }.isFailure)
        }
    }

    @Test fun cancellation_is_simulation_and_duplicate_is_suggestion() {
        val scenario = PriceAnalysis.simulateCancellation(listOf(quote(PricePeriod.MONTHLY, 2000), quote(PricePeriod.YEARLY, 20000)))
        assertEquals(44000, scenario.estimatedAnnualAvoidedMinor)
        assertTrue(scenario.note.contains("并非已节省"))
        assertTrue(runCatching { PriceAnalysis.simulateCancellation(listOf(quote(PricePeriod.MONTHLY, 2000), quote(PricePeriod.YEARLY, 20000).copy(currency = "CNY"))) }.isFailure)
        assertEquals(1, PriceAnalysis.possibleDuplicates(listOf("a" to "Example", "b" to " example ", "c" to "Other")).size)
    }
}
