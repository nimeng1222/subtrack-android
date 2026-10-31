package com.subtrack.network

import org.junit.Assert.*
import org.junit.Test

class TemplateCacheCodecTest {
    private val source = "https://example.com/catalog?token=do-not-persist"
    private val data = """{"schemaVersion":1,"dataVersion":"v1","templates":[{"id":"one","serviceName":"Example"}]}"""

    @Test fun provenance_survives_reload_and_does_not_persist_url_or_query() {
        val hash = TemplateCacheCodec.sourceHash(source)
        val encoded = TemplateCacheCodec.encode(hash, 1234L, data)
        assertFalse(encoded.contains("do-not-persist"))
        assertEquals(1234L to data, TemplateCacheCodec.decode(hash, encoded))
        assertEquals("https://example.com/catalog", TemplateCacheCodec.sourceLabel(source))
        assertEquals("one", TemplateCodec.decode(TemplateCacheCodec.decode(hash, encoded).second).single().id)
    }

    @Test fun other_source_wrong_version_and_damage_do_not_decode() {
        val hash = TemplateCacheCodec.sourceHash(source)
        val encoded = TemplateCacheCodec.encode(hash, 1234L, data)
        assertTrue(runCatching { TemplateCacheCodec.decode(TemplateCacheCodec.sourceHash("https://other.example"), encoded) }.isFailure)
        assertTrue(runCatching { TemplateCacheCodec.decode(hash, encoded.replace("\"cacheVersion\": 1", "\"cacheVersion\": 2")) }.isFailure)
        assertTrue(runCatching { TemplateCacheCodec.decode(hash, encoded.dropLast(3)) }.isFailure)
    }

    @Test fun old_cache_is_usable_without_inventing_fetch_time() {
        val (fetchedAt, payload) = TemplateCacheCodec.decode(TemplateCacheCodec.sourceHash(source), data)
        assertNull(fetchedAt)
        assertEquals("Example", TemplateCodec.decode(payload).single().serviceName)
    }

    @Test fun unsafe_sources_are_not_rendered_as_links() {
        assertEquals("无效服务地址", TemplateCacheCodec.sourceLabel("https://user:secret@example.com/catalog"))
        assertEquals("无效服务地址", TemplateCacheCodec.sourceLabel("javascript:alert(1)"))
    }
}
