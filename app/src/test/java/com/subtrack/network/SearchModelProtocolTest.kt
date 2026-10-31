package com.subtrack.network

import com.subtrack.domain.analysis.PriceIssueType
import com.subtrack.domain.analysis.PricePeriod
import com.subtrack.network.search.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * F5: the documented search protocol (Brave-compatible) sends its key ONLY to the search endpoint, the model extraction
 * protocol sends its key ONLY to the model endpoint, and the model can only propose candidates that the local validator
 * accepts/rejects on real evidence. Controlled loopback servers stand in for the providers (no paid calls, no real keys).
 */
class SearchModelProtocolTest {
    private val searchKey = "test-search-key-0001"
    private val modelKey = "test-model-key-0002"

    private data class Captured(val requestLine: String, val headers: Map<String, String>, val body: String)

    /** One-shot raw HTTP server on loopback; captures the request without logging keys. */
    private class RawServer(private val respond: (Captured) -> Pair<Int, String>) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val thread = Thread {
            try {
                server.accept().use { socket: Socket ->
                    socket.soTimeout = 5_000
                    val input = java.io.BufferedInputStream(socket.getInputStream())
                    fun line(): String {
                        val out = StringBuilder()
                        while (true) {
                            val c = input.read()
                            if (c < 0) return out.toString()
                            if (c == '\n'.code) break
                            if (c != '\r'.code) out.append(c.toChar())
                        }
                        return out.toString()
                    }
                    val requestLine = line()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        val idx = h.indexOf(':')
                        if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length).also { bytes ->
                        var read = 0
                        while (read < length) {
                            val n = input.read(bytes, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                    }
                    val (code, payload) = respond(Captured(requestLine, headers, String(body, Charsets.UTF_8)))
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            runCatching { server.close() }
            thread.join(2_000)
        }
    }

    private fun query() = PriceQueryRequest(
        service = "Sample", region = "US", plan = "Pro", currency = "USD", period = PricePeriod.MONTHLY,
        taxTerms = "before tax", discountTerms = "no promotion", benefitTerms = "same benefits", newUserOnly = false,
    )


    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun chatResponse(content: String): String =
        "{\"choices\":[{\"message\":{\"content\":" + jsonString(content) + "}}]}"

    /** Deterministic retrieval material that exactly matches the controlled model response below. */
    private fun controlledDocuments() = listOf(
        com.subtrack.domain.analysis.RetrievedDocument(
            url = "https://fixture.subtrack.invalid/sample-pro",
            body = "Sample Pro costs USD 9.99 per month before tax.",
            sourceType = com.subtrack.domain.analysis.SourceType.THIRD_PARTY,
            pageDate = null,
            retrievedAtMillis = 1_793_225_600_000L,
            provenance = com.subtrack.domain.analysis.Provenance.ONLINE_HTTP,
        ),
    )

    private fun bravesBody() = """
        {"web":{"results":[{"title":"Sample Pro pricing","url":"https://fixture.subtrack.invalid/sample-pro",
        "description":"Sample Pro costs USD 9.99 per month before tax.","age":"1 day"}]}}
    """.trimIndent()

    private fun modelContent(sourceUrl: String, amountMinor: Long, evidence: String) = """
        {"quotes":[{"id":"model-1","service":"Sample","plan":"Pro","region":"US","currency":"USD",
        "amountMinor":$amountMinor,"billingPeriod":"MONTHLY","taxTerms":"before tax","discountTerms":"no promotion",
        "benefitTerms":"same benefits","newUserOnly":false,"sourceUrl":"$sourceUrl","evidence":"$evidence"}]}
    """.trimIndent()

    @Test fun search_key_is_sent_only_to_the_search_endpoint_and_brave_response_parses() = runBlocking {
        var captured: Captured? = null
        RawServer { req ->
            captured = req
            200 to """{"web":{"results":[{"title":"Sample Pro pricing","url":"https://fixture.subtrack.invalid/sample-pro","description":"Sample Pro costs USD 9.99 per month before tax."}]}}"""
        }.use { server ->
            val adapter = HttpSearchRetrievalAdapter(
                HttpSearchConfig(
                    endpoint = "http://127.0.0.1:${server.port}/res/v1/web/search",
                    apiKey = searchKey, enabled = true, allowLocalDebug = true,
                ),
                RestrictedFetcher(debug = true),
            )
            val bundle = adapter.retrieve(query())
            assertEquals(1, bundle.documents.size)
            assertEquals("https://fixture.subtrack.invalid/sample-pro", bundle.documents.single().url)
            assertTrue(bundle.documents.single().body.contains("USD 9.99 per month"))
            assertEquals("ONLINE_HTTP", bundle.documents.single().provenance.name)
        }
        val req = requireNotNull(captured)
        assertEquals("GET /res/v1/web/search?q=Sample+Pro+US+USD+price+monthly&count=5 HTTP/1.1", req.requestLine)
        assertEquals(searchKey, req.headers["x-subscription-token"])
        assertNull("search request must not carry a bearer token", req.headers["authorization"])
    }

    @Test fun search_auth_failures_are_explicit_and_never_echo_the_key() = runBlocking {
        for ((status, label) in listOf(401 to "401", 403 to "403", 429 to "429")) {
            RawServer { _ -> status to "{}" }.use { server ->
                val adapter = HttpSearchRetrievalAdapter(
                    HttpSearchConfig(
                        endpoint = "http://127.0.0.1:${server.port}/res/v1/web/search",
                        apiKey = searchKey, enabled = true, allowLocalDebug = true,
                    ),
                    RestrictedFetcher(debug = true),
                )
                val error = runCatching { adapter.retrieve(query()) }.exceptionOrNull()
                assertNotNull("HTTP $label must fail explicitly", error)
                assertTrue("status must be named", error!!.message!!.contains(label))
                assertFalse("error text must not echo the key", error.message!!.contains(searchKey))
            }
        }
    }

    @Test fun empty_search_results_never_become_a_price_conclusion() = runBlocking {
        RawServer { _ -> 200 to """{"web":{"results":[]}}""" }.use { server ->
            val adapter = HttpSearchRetrievalAdapter(
                HttpSearchConfig(
                    endpoint = "http://127.0.0.1:${server.port}/res/v1/web/search",
                    apiKey = searchKey, enabled = true, allowLocalDebug = true,
                ),
                RestrictedFetcher(debug = true),
            )
            val report = PriceAnalysisPipeline().run(adapter, query(), System.currentTimeMillis())
            assertTrue(report.documents.isEmpty())
            assertTrue(report.accepted.isEmpty())
            assertTrue("must state empty result explicitly", report.statusMessage.contains("空结果"))
        }
    }

    @Test fun model_key_is_sent_only_to_the_model_endpoint_and_model_quotes_are_locally_bound() = runBlocking {
        RawServer { _ -> 200 to "" }.use { searchServer ->
            var modelRequest: Captured? = null
            RawServer { req ->
                modelRequest = req
                200 to chatResponse(modelContent("https://fixture.subtrack.invalid/sample-pro", 999, "USD 9.99 per month"))
            }.use { modelServer ->
                val adapter = HttpSearchRetrievalAdapter(
                    HttpSearchConfig(
                        endpoint = "http://127.0.0.1:${searchServer.port}/web/search",
                        apiKey = searchKey, enabled = true, allowLocalDebug = true,
                    ),
                    RestrictedFetcher(debug = true),
                )
                // Documents come from a deterministic retrieval bundle so the model material is controlled.
                val documents = controlledDocuments()
                val extractor = CompositeQuoteExtractor(
                    listOf(
                        ModelQuoteExtractor(
                            ModelExtractionConfig(
                                endpoint = "http://127.0.0.1:${modelServer.port}/v1/chat/completions",
                                model = "controlled-model", apiKey = modelKey, enabled = true, allowLocalDebug = true,
                            ),
                            NetworkTransport(debug = true),
                        ),
                    ),
                )
                val extraction = extractor.extract(query(), documents)
                        assertEquals(1, extraction.candidates.size)
                val candidate = extraction.candidates.single()
                assertEquals(999, candidate.amountMinor)
                assertEquals("https://fixture.subtrack.invalid/sample-pro", candidate.sourceUrl)
                assertEquals(documents.single().retrievedAtMillis, candidate.retrievedAtMillis)

                val req = requireNotNull(modelRequest)
                assertEquals(modelKey, req.headers["authorization"]?.removePrefix("Bearer "))
                assertNull("model request must not carry the search key", req.headers["x-subscription-token"])
                assertFalse("body must not contain the search key", req.body.contains(searchKey))
                assertTrue("body carries the retrieval snippet", req.body.contains("fixture.subtrack.invalid"))
                assertTrue(req.body.contains("controlled-model"))
            }
        }
    }

    @Test fun model_cannot_fabricate_sources_or_amounts() = runBlocking {
        val documents = controlledDocuments()
        fun pipelineWith(content: String): Pair<PriceAnalysisReport, List<PriceIssueType>> = runBlocking {
            RawServer { _ ->
                200 to chatResponse(content)
            }.use { server ->
                val extractor = ModelQuoteExtractor(
                    ModelExtractionConfig(
                        endpoint = "http://127.0.0.1:${server.port}/v1/chat/completions",
                        model = "controlled-model", apiKey = modelKey, enabled = true, allowLocalDebug = true,
                    ),
                    NetworkTransport(debug = true),
                )
                val bundle = RetrievalBundle("fixture", query(), documents, "fixture", true)
                val report = PriceAnalysisPipeline(extractor).run(
                    object : PriceRetrievalAdapter {
                        override val id = bundle.adapterId
                        override val isFixture = true
                        override val requiresUserConsent = false
                        override suspend fun retrieve(query: PriceQueryRequest) = bundle
                    },
                    query(), System.currentTimeMillis(),
                )
                report to report.rejected.map { it.type }
            }
        }
        // Fabricated source URL must be rejected (validator refuses sources outside the retrieved documents).
        val (forged, forgedTypes) = pipelineWith(modelContent("https://evil.example/forged", 999, "USD 9.99 per month"))
        assertTrue(forged.accepted.isEmpty())
        assertTrue("fabricated source must be named: $forgedTypes", forgedTypes.contains(PriceIssueType.FABRICATED_SOURCE))

        // Amount that contradicts the evidence must be rejected.
        val (mismatch, mismatchTypes) = pipelineWith(modelContent("https://fixture.subtrack.invalid/sample-pro", 1000, "USD 9.99 per month"))
        assertTrue(mismatch.accepted.isEmpty())
        assertTrue("amount mismatch must be named: $mismatchTypes", mismatchTypes.any {
            it == PriceIssueType.AMOUNT_MISMATCH || it == PriceIssueType.PERIOD_CONFUSION
        })
    }

    @Test fun model_auth_failure_is_explicit_and_never_echoes_the_key() = runBlocking {
        RawServer { _ -> 401 to "{}" }.use { server ->
            val documents = controlledDocuments()
            val extractor = ModelQuoteExtractor(
                ModelExtractionConfig(
                    endpoint = "http://127.0.0.1:${server.port}/v1/chat/completions",
                    model = "controlled-model", apiKey = modelKey, enabled = true, allowLocalDebug = true,
                ),
                NetworkTransport(debug = true),
            )
            val error = runCatching { runBlocking { extractor.extract(query(), documents) } }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("401"))
            assertFalse(error.message!!.contains(modelKey))
        }
    }
}
