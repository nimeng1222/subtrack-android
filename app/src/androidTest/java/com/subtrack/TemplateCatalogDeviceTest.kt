package com.subtrack

import com.subtrack.network.NetworkTransport
import com.subtrack.network.OnlineRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Fixture endpoint only; no personal data or live pricing. */
class TemplateCatalogDeviceTest {
    @Test fun fetched_catalog_persists_source_and_time_and_bad_refresh_keeps_previous_cache() = runBlocking {
        val context = Round2Instrumentation.instance.targetContext
        val source = "http://127.0.0.1:18080/templates"
        val repo = OnlineRepository(context, NetworkTransport(true))
        val before = System.currentTimeMillis()
        val fetched = repo.templates(source)
        val after = System.currentTimeMillis()
        val restored = OnlineRepository(context, NetworkTransport(true)).cachedCatalog(source)!!
        assertEquals(fetched.map { it.id }, restored.templates.map { it.id })
        assertEquals(source, restored.source)
        assertTrue(restored.fetchedAtUtcMillis!! in before..after)
        assertNull(OnlineRepository(context, NetworkTransport(true)).cachedCatalog("http://127.0.0.1:18080/other"))
        try { repo.templates("http://127.0.0.1:18080/invalid"); fail("invalid response accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(restored, repo.cachedCatalog(source))
    }
}
