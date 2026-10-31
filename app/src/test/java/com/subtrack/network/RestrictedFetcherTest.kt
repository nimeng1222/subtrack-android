package com.subtrack.network.search

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RestrictedFetcherTest {
    private fun resolverReturning(vararg ips: String) = HostResolver { _ -> ips.map { InetAddress.getByName(it) } }

    private fun rejects(block: () -> Unit) {
        try {
            block(); fail("must reject")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun public_https_host_passes_policy() {
        UrlSafety.check("https://shop.example.com/plan", debug = false, resolver = resolverReturning("93.184.216.34"))
    }

    @Test fun metadata_link_local_is_blocked_even_in_debug() {
        rejects { UrlSafety.check("https://169.254.169.254/latest/meta-data", debug = true, resolver = resolverReturning("169.254.169.254")) }
        rejects { UrlSafety.check("https://metadata.google.internal/", debug = true, resolver = resolverReturning("169.254.169.254")) }
    }

    @Test fun private_and_loopback_ips_are_blocked_for_https() {
        rejects { UrlSafety.check("https://internal.example.com/", resolver = resolverReturning("10.0.0.5")) }
        rejects { UrlSafety.check("https://internal.example.com/", resolver = resolverReturning("192.168.1.1")) }
        rejects { UrlSafety.check("https://internal.example.com/", resolver = resolverReturning("172.20.0.1")) }
        rejects { UrlSafety.check("https://127.0.0.1/", resolver = resolverReturning("127.0.0.1")) }
        rejects { UrlSafety.check("https://[::1]/", resolver = resolverReturning("::1")) }
        rejects { UrlSafety.check("https://[fd00::1]/", resolver = resolverReturning("fd00::1")) }
    }

    @Test fun cgnat_address_is_blocked() {
        rejects { UrlSafety.check("https://carrier.example.com/", resolver = resolverReturning("100.64.1.1")) }
    }

    @Test fun http_is_only_allowed_for_local_debug_targets() {
        rejects { UrlSafety.check("http://127.0.0.1:8080/", debug = false, resolver = resolverReturning("127.0.0.1")) }
        rejects { UrlSafety.check("http://example.com/", debug = true, resolver = resolverReturning("93.184.216.34")) }
        UrlSafety.check("http://127.0.0.1:18080/slow", debug = true, resolver = resolverReturning("127.0.0.1"))
        UrlSafety.check("http://10.0.2.2:8080/x", debug = true, resolver = resolverReturning("10.0.2.2"))
    }

    @Test fun credentials_fragments_and_bad_schemes_are_rejected() {
        rejects { UrlSafety.check("https://user:pass@shop.example.com/", resolver = resolverReturning("93.184.216.34")) }
        rejects { UrlSafety.check("https://shop.example.com/#frag", resolver = resolverReturning("93.184.216.34")) }
        rejects { UrlSafety.check("file:///etc/passwd") }
        rejects { UrlSafety.check("javascript:alert(1)") }
    }

    /** Minimal raw HTTP/1.1 server on loopback (java.base only, no com.sun.net.httpserver on Android). */
    private suspend fun withRawServer(handler: (OutputStream) -> Unit, block: suspend (String) -> Unit) {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var line = reader.readLine()
                    while (line != null && line.isNotEmpty()) line = reader.readLine()
                    handler(socket.getOutputStream())
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        try {
            block("http://127.0.0.1:${server.localPort}/resource")
        } finally {
            runCatching { server.close() }
            thread.join(3000)
        }
    }

    private fun response(code: Int, reason: String, body: ByteArray, extraHeaders: String = ""): ByteArray {
        val head = "HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extraHeaders\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    @Test fun fetches_loopback_local_debug_page() = runBlocking {
        withRawServer({ out -> out.write(response(200, "OK", "hello".toByteArray())); out.flush() }) { url ->
            val resource = RestrictedFetcher(debug = true).fetch(url, maxBytes = 1024, timeoutMillis = 3000)
            assertEquals("hello", resource.body)
            assertTrue(resource.retrievedAtMillis > 0)
        }
    }

    @Test fun redirect_is_refused_so_ssrf_cannot_bypass_policy() = runBlocking {
        withRawServer({ out ->
            out.write(response(302, "Found", ByteArray(0), "Location: https://169.254.169.254/latest/meta-data\r\n"))
            out.flush()
        }) { url ->
            val error = runCatching { RestrictedFetcher(debug = true).fetch(url, maxBytes = 1024, timeoutMillis = 3000) }
            assertTrue(error.isFailure)
            assertTrue(error.exceptionOrNull()!!.message!!.contains("重定向"))
        }
    }

    @Test fun oversize_response_is_cut_off() = runBlocking {
        withRawServer({ out ->
            out.write(response(200, "OK", ByteArray(5000) { it.toByte() })); out.flush()
        }) { url ->
            val error = runCatching { RestrictedFetcher(debug = true).fetch(url, maxBytes = 1024, timeoutMillis = 3000) }
            assertTrue(error.isFailure)
            assertTrue(error.exceptionOrNull()!!.message!!.contains("上限"))
        }
    }

    @Test fun slow_response_times_out() = runBlocking {
        withRawServer({ out ->
            Thread.sleep(1500)
            runCatching { out.write(response(200, "OK", "late".toByteArray())); out.flush() }
        }) { url ->
            val error = runCatching { RestrictedFetcher(debug = true).fetch(url, maxBytes = 1024, timeoutMillis = 300) }
            assertTrue(error.isFailure)
        }
    }

    /**
     * F6: the address set validated by UrlSafety is the exact set that gets dialed. A resolver that would answer
     * "private" on a second lookup (DNS rebinding) cannot redirect the connection, because the fetcher never resolves
     * again after the policy check.
     */
    @Test fun connection_uses_only_the_validated_addresses_no_rebinding() {
        val lookups = java.util.concurrent.atomic.AtomicInteger()
        val publicAddress = InetAddress.getByName("93.184.216.34")
        val privateAddress = InetAddress.getByName("10.0.0.5")
        val resolver = HostResolver { _ ->
            if (lookups.incrementAndGet() == 1) listOf(publicAddress) else listOf(privateAddress)
        }
        val dialed = mutableListOf<InetAddress>()
        val fetcher = RestrictedFetcher(
            resolver = resolver,
            dial = { address, _, _ ->
                dialed += address
                throw java.io.IOException("stop before any real connection")
            },
        )
        val failure = runCatching { runBlocking { fetcher.fetch("https://rebind.example.com/plan", timeoutMillis = 2_000) } }
        assertTrue(failure.isFailure)
        assertEquals("policy must resolve exactly once", 1, lookups.get())
        assertEquals("only the validated public address may be dialed", listOf(publicAddress), dialed)
    }

    @Test fun private_resolution_is_rejected_before_any_dial() {
        var dialed = 0
        val fetcher = RestrictedFetcher(
            resolver = resolverReturning("10.0.0.5"),
            dial = { _, _, _ -> dialed++; throw java.io.IOException("must not dial") },
        )
        val failure = runCatching { runBlocking { fetcher.fetch("https://internal.example.com/", timeoutMillis = 1_000) } }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals("SSRF policy must reject before dialing", 0, dialed)
    }

    @Test fun all_validated_addresses_are_tried_in_order() {
        val first = InetAddress.getByName("93.184.216.34")
        val second = InetAddress.getByName("1.1.1.1")
        val dialed = mutableListOf<InetAddress>()
        val fetcher = RestrictedFetcher(
            resolver = HostResolver { _ -> listOf(first, second) },
            dial = { address, _, _ ->
                dialed += address
                throw java.io.IOException("dial failed")
            },
        )
        val failure = runCatching { runBlocking { fetcher.fetch("https://multi.example.com/", timeoutMillis = 1_000) } }
        assertTrue(failure.isFailure)
        assertEquals("every validated address is attempted (bounded), in order", listOf(first, second), dialed)
    }

    @Test fun ipv4_mapped_ipv6_private_addresses_are_blocked() {
        rejects { UrlSafety.check("https://mapped.example.com/", resolver = resolverReturning("::ffff:10.0.0.5")) }
        rejects { UrlSafety.check("https://mapped.example.com/", resolver = resolverReturning("::ffff:192.168.1.1")) }
        rejects { UrlSafety.check("https://mapped.example.com/", resolver = resolverReturning("::ffff:127.0.0.1")) }
    }

    @Test fun excessive_resolution_answers_are_rejected() {
        rejects { UrlSafety.check("https://many.example.com/", resolver = HostResolver { _ -> (1..17).map { InetAddress.getByName("93.184.216.$it") } }) }
    }

    @Test fun slow_trickle_is_cut_by_the_absolute_deadline() = runBlocking {
        withRawServer({ out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val started = System.nanoTime()
            while (System.nanoTime() - started < 3_000_000_000L) {
                runCatching { out.write('x'.code); out.flush() }
                Thread.sleep(40)
            }
        }) { url ->
            val started = System.nanoTime()
            val error = runCatching { RestrictedFetcher(debug = true).fetch(url, maxBytes = 1024, timeoutMillis = 150) }
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            assertTrue("must time out", error.isFailure)
            assertTrue("absolute deadline must cut the trickle (took ${elapsed}ms)", elapsed < 500)
        }
    }

    @Test fun truncated_content_length_is_rejected() = runBlocking {
        withRawServer({ out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{}".toByteArray())
            out.flush()
        }) { url ->
            val error = runCatching { RestrictedFetcher(debug = true).fetch(url, maxBytes = 1024, timeoutMillis = 2_000) }
            assertTrue("a short body with a declared Content-Length must never be a success", error.isFailure)
        }
    }

    @Test fun active_cancellation_closes_the_socket_before_the_deadline() = runBlocking {
        withRawServer({ out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val started = System.nanoTime()
            while (System.nanoTime() - started < 4_000_000_000L) {
                runCatching { out.write('x'.code); out.flush() }
                Thread.sleep(40)
            }
        }) { url ->
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { RestrictedFetcher(debug = true).fetch(url, 1024, 5_000) }
            }
            delay(150)
            val started = System.nanoTime()
            job.cancelAndJoin()
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            assertTrue("cancellation must abort the active read (took ${elapsed}ms)", elapsed < 500)
            assertTrue(job.isCancelled)
        }
    }

    @Test fun cancelled_request_does_not_return_a_late_result() = runBlocking {
        withRawServer({ out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
            Thread.sleep(1_200)
            runCatching { out.write("abc".toByteArray()); out.flush() }
        }) { url ->
            var result: Any? = null
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                result = runCatching { RestrictedFetcher(debug = true).fetch(url, 1024, 5_000) }.getOrNull()
            }
            delay(80)
            job.cancelAndJoin()
            delay(1_500)
            assertNull("a cancelled request must never publish a late body", result)
        }
    }

    @Test fun cancellation_is_reported_as_cancellation_not_a_socket_error() = runBlocking {
        withRawServer({ out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val started = System.nanoTime()
            while (System.nanoTime() - started < 3_000_000_000L) {
                runCatching { out.write('x'.code); out.flush() }
                Thread.sleep(40)
            }
        }) { url ->
            val siblingRan = java.util.concurrent.atomic.AtomicBoolean(false)
            val sibling = launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(400); siblingRan.set(true)
            }
            // No runCatching around the fetch: the caller must observe cancellation semantics, not SocketException.
            val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            val fetch = scope.async {
                RestrictedFetcher(debug = true).fetch(url, 1024, 5_000)
            }
            delay(150)
            fetch.cancel()
            val error = runCatching { fetch.await() }.exceptionOrNull()
            assertTrue("cancelled await must surface CancellationException, got $error", error is kotlinx.coroutines.CancellationException)
            assertTrue("fetch must be cancelled", fetch.isCancelled)
            sibling.join()
            assertTrue("a sibling task must keep running after cancellation", siblingRan.get())
            scope.cancel()
        }
    }
}
