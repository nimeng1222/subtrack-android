package com.subtrack

import com.subtrack.network.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Uses scripts/network-fixture.py and adb reverse; does not contact paid model services. */
class NetworkDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private val base = "http://127.0.0.1:18080"

    @Test fun network_real_http_templates_and_invalid_cache_preservation() = runBlocking {
        val repo = OnlineRepository(context, NetworkTransport(true))
        val first = repo.templates("$base/templates")
        assertEquals(4, first.size)
        try { repo.templates("$base/invalid"); fail("invalid schema accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(first.map { it.id }, repo.cachedTemplates().map { it.id })
    }

    @Test fun network_oversize_redirect_quota_and_bad_key_fail_not_empty_success() = runBlocking {
        for (path in listOf("huge", "redirect", "quota", "bad-key")) {
            try { NetworkTransport(true).request("$base/$path"); fail("must fail $path") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun network_cancellation_disconnects_without_success() = runBlocking {
        val job = async(Dispatchers.IO) { NetworkTransport(true).request("$base/slow") }
        delay(200); job.cancel()
        try { job.await(); fail("cancelled request succeeded") } catch (_: CancellationException) { }
    }

    @Test fun network_byok_keystore_roundtrip_and_clear() {
        val secrets = SecretStore(context)
        try {
            secrets.put("isolated-test-not-real-key")
            assertEquals("isolated-test-not-real-key", secrets.get())
            val file = java.io.File(context.noBackupFilesDir, "network-key.bin")
            assertFalse(file.readText().contains("isolated-test-not-real-key"))
            secrets.clear(); assertNull(secrets.get())
        } finally { secrets.clear() }
    }

    @Test fun network_key_is_bound_to_endpoint_and_clear_removes_it() {
        val secrets = SecretStore(context)
        try {
            secrets.putBound("https://one.example/v1", "synthetic-key")
            assertEquals("synthetic-key", secrets.getBound("https://one.example/v1"))
            assertNull(secrets.getBound("https://two.example/v1"))
            secrets.clear()
            assertNull(secrets.getBound("https://one.example/v1"))
        } finally { secrets.clear() }
    }

    @Test fun network_template_cache_is_bound_to_source() = runBlocking {
        val repo = OnlineRepository(context, NetworkTransport(true))
        repo.templates("$base/templates")
        assertEquals(4, repo.cachedTemplates("$base/templates").size)
        assertTrue(repo.cachedTemplates("$base/other-source").isEmpty())
    }

    @Test fun network_openai_compatible_controlled_protocol_not_real_model() = runBlocking {
        val q = PriceQuestion("Sample", "US", "Pro", "USD", "MONTHLY", "before tax", "https://example.com/price", "USD 20 per month")
        val bytes = NetworkTransport(true).request("$base/v1/chat/completions", AiProtocol.request("fixture-not-model", q), "test-only")
        val result = AiProtocol.decode(bytes.toString(Charsets.UTF_8), q, 1234)
        assertEquals(2000L, result.amountMinor)
        assertEquals("未进行在线核验", result.verification)
    }
}
