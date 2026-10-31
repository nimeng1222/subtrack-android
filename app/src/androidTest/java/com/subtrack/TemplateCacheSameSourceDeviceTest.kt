package com.subtrack

import com.subtrack.network.NetworkTransport
import com.subtrack.network.OnlineRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Closes the flagged N1 gap: the previous "invalid" case used a DIFFERENT url, so it did not prove that a source which
 * was good and then becomes bad keeps its own last-good cache. Here the SAME url returns a valid catalog first and an
 * invalid schema on the next fetch; the last-good cache must be retained and must not leak across sources.
 */
class TemplateCacheSameSourceDeviceTest {
    @Test fun same_source_good_then_bad_keeps_previous_cache() = runBlocking {
        val context = Round2Instrumentation.instance.targetContext
        val base = "http://127.0.0.1:18080"
        val url = "$base/templates-flaky-${UUID.randomUUID()}"   // fresh server state per run
        val repo = OnlineRepository(context, NetworkTransport(true))

        val first = repo.templates(url)                           // real HTTP: valid catalog
        assertEquals(4, first.size)
        val cachedAfterGood = repo.cachedCatalog(url)!!
        assertEquals(first.map { it.id }, cachedAfterGood.templates.map { it.id })
        assertNotNull(cachedAfterGood.fetchedAtUtcMillis)

        // Same URL turns bad on the next fetch. Respect the transport's per-URL 2s throttle with a real wait so this
        // exercises the invalid-schema path rather than the throttle guard.
        Thread.sleep(2_100)
        val failure = runCatching { OnlineRepository(context, NetworkTransport(true)).templates(url) }
        assertNotNull("same-url invalid refresh must fail", failure.exceptionOrNull())

        // Previous good cache is retained for THIS source, and unrelated sources have no cache (no cross-source leak).
        assertEquals(first.map { it.id }, repo.cachedCatalog(url)!!.templates.map { it.id })
        assertNull(repo.cachedCatalog("$base/other-${UUID.randomUUID()}"))
    }
}
