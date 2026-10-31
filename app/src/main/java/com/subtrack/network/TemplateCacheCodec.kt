package com.subtrack.network

import com.subtrack.data.backup.*
import java.net.URI
import java.security.MessageDigest

/** Store response and provenance in one atomically replaced file. Never persist URL query credentials. */
internal object TemplateCacheCodec {
    fun sourceHash(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun sourceLabel(url: String): String = runCatching {
        val uri = URI(url)
        require(uri.host != null && uri.userInfo == null && uri.scheme in setOf("https", "http"))
        URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    }.getOrDefault("无效服务地址")

    fun encode(sourceHash: String, fetchedAt: Long, payload: String): String {
        require(fetchedAt > 0)
        return JsonValue.Obj(mapOf("cacheVersion" to JsonValue.Num("1"),
            "sourceHash" to JsonValue.Str(sourceHash), "fetchedAtUtcMillis" to JsonValue.Num(fetchedAt.toString()),
            "payload" to JsonValue.Str(payload))).render()
    }

    fun decode(sourceHash: String, raw: String): Pair<Long?, String> {
        require(raw.length <= 2_097_152)
        val obj = JsonParser(raw).parseDocument().asObject("缓存")
        // Older cached catalogues have no provenance timestamp. Do not invent one.
        if (obj.optional("cacheVersion") == null) return null to raw
        require(obj.number("cacheVersion") == 1L && obj.text("sourceHash") == sourceHash)
        val fetchedAt = obj.number("fetchedAtUtcMillis") ?: error("缺少缓存时间")
        require(fetchedAt > 0)
        val payload = obj.required("payload", "缓存").asString("缓存.payload")
        require(payload.length <= 1_048_576)
        return fetchedAt to payload
    }
}
