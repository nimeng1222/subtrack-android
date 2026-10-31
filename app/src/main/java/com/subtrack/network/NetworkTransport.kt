package com.subtrack.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NetworkUrls {
    fun validate(raw: String, debug: Boolean = false): URI {
        val u = URI(raw)
        require(u.host != null && u.userInfo == null && u.fragment == null) { "无效服务地址" }
        require(u.scheme == "https" || (debug && u.scheme == "http" &&
            u.host in setOf("10.0.2.2", "localhost", "127.0.0.1"))) { "仅支持 HTTPS；Debug 仅允许模拟器/本机 HTTP" }
        return u
    }
    fun external(raw: String): Boolean = runCatching { validate(raw); true }.getOrDefault(false)
}

/** No redirects: a configured endpoint cannot silently forward BYOK credentials elsewhere. */
class NetworkTransport(private val debug: Boolean = false) {
    private val last = ConcurrentHashMap<String, Long>()

    /**
     * 发一次请求。
     *
     * 默认**不跟随重定向**：配置好的端点不能用 302 把凭据带去别的主机。
     * 唯一例外是更新包下载（GitHub 资产会 302 到 objects.githubusercontent.com）：
     * 调用方必须显式传 [redirectHosts]，跳转目标只允许其中的 https 主机，且最多 3 跳；
     * 跳转后不携带任何 Authorization 头。
     */
    suspend fun request(url: String, json: String? = null, key: String? = null,
                        maxBytes: Int = 1_048_576, throttle: Boolean = true,
                        maxRequestBytes: Int = 65_536, setupToken: String? = null,
                        accept: String = "application/json",
                        userAgent: String? = null,
                        redirectHosts: Set<String> = emptySet()): ByteArray = withTimeout(25_000) { withContext(Dispatchers.IO) {
        val uri = NetworkUrls.validate(url, debug)
        require(maxRequestBytes in 1..1_048_576)
        require(key == null || (!key.contains('\r') && !key.contains('\n')))
        require(setupToken == null || (setupToken.length in 32..200 && !setupToken.contains('\r') && !setupToken.contains('\n')))
        require(userAgent == null || (!userAgent.contains('\r') && !userAgent.contains('\n')))
        if (throttle) synchronized(last) {
            val now = System.nanoTime()
            require(now - (last[url] ?: Long.MIN_VALUE / 2) >= 2_000_000_000) { "请求过于频繁，请稍后重试" }
            if (last.size > 32) last.clear()
            last[url] = now
        }
        var target = uri
        var hops = 0
        while (true) {
            val connection = target.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", accept)
            if (!key.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            if (setupToken != null) connection.setRequestProperty("X-Setup-Token", setupToken)
            if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent)
            var redirectTo: java.net.URI? = null
            val body: ByteArray? = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    if (json != null) {
                        val bytes = json.toByteArray(Charsets.UTF_8)
                        require(bytes.size <= maxRequestBytes) { "发送材料过大" }
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                        require(location != null && redirectHosts.isNotEmpty() && hops < 3) {
                            "服务返回重定向（HTTP $code），已拒绝跟随"
                        }
                        val next = target.resolve(location)
                        require(next.scheme == "https" && next.host in redirectHosts) {
                            "重定向到未授权地址：${next.host}"
                        }
                        redirectTo = next
                        if (continuation.isActive) continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    require(code in 200..299) { "服务请求失败 HTTP $code" }
                    require(connection.contentLengthLong <= maxBytes) { "响应超过上限" }
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (continuation.isActive) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            require(output.size() + n <= maxBytes) { "响应超过上限" }
                            output.write(buffer, 0, n)
                        }
                    }
                    if (continuation.isActive) continuation.resume(output.toByteArray())
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally { connection.disconnect() }
            }
            if (body != null) return@withContext body
            target = requireNotNull(redirectTo) { "重定向缺少地址" }
            hops++
        }
        @Suppress("UNREACHABLE_CODE")
        ByteArray(0)
    } }
}
