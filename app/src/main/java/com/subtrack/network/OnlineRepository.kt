package com.subtrack.network

import android.content.Context
import android.util.AtomicFile
import com.subtrack.domain.model.SubscriptionTemplate
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class OnlineRepository(context: Context, private val transport: NetworkTransport) {
    private val directory = context.filesDir
    private var selectedSource: String? = null
    private fun cache(url: String): AtomicFile {
        val hash = TemplateCacheCodec.sourceHash(url)
        return AtomicFile(File(directory, "templates-$hash.json"))
    }
    fun cachedTemplates(): List<SubscriptionTemplate> = runCatching {
        cachedTemplates(selectedSource ?: return emptyList())
    }.getOrDefault(emptyList())
    fun cachedTemplates(url: String): List<SubscriptionTemplate> = cachedCatalog(url)?.templates.orEmpty()

    fun cachedCatalog(url: String): TemplateCatalog? = runCatching {
        val file = cache(url)
        val bytes = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= 2_097_152)
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        val (fetchedAt, payload) = TemplateCacheCodec.decode(TemplateCacheCodec.sourceHash(url),
            bytes.toString(Charsets.UTF_8))
        TemplateCatalog(TemplateCodec.decode(payload), TemplateCacheCodec.sourceLabel(url), fetchedAt)
    }.getOrNull()

    suspend fun templates(url: String): List<SubscriptionTemplate> {
        val bytes = transport.request(url)
        val payload = bytes.toString(Charsets.UTF_8)
        val result = TemplateCodec.decode(payload)
        currentCoroutineContext().ensureActive()
        selectedSource = url
        val cache = cache(url)
        val encoded = TemplateCacheCodec.encode(TemplateCacheCodec.sourceHash(url), System.currentTimeMillis(), payload)
            .toByteArray(Charsets.UTF_8)
        synchronized(cacheLock) {
            val stream = cache.startWrite()
            try { stream.write(encoded); cache.finishWrite(stream) }
            catch (e: Exception) { cache.failWrite(stream); throw e }
        }
        return result
    }
    companion object {
        private val cacheLock = Any()
        val offlineTemplates = listOf(
            SubscriptionTemplate("offline-music-cn", "音乐会员", "中国大陆", "请确认套餐", "CNY", "MONTHLY"),
            SubscriptionTemplate("offline-cloud-us", "云存储", "美国", "请确认套餐", "USD", "YEARLY"),
            SubscriptionTemplate("offline-tool-eu", "软件工具", "欧盟", "请确认套餐", "EUR", "MONTHLY"),
        )
    }
}

data class TemplateCatalog(val templates: List<SubscriptionTemplate>, val source: String, val fetchedAtUtcMillis: Long?)
