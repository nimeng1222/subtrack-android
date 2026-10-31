package com.subtrack.network.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 受控来源抓取（客户端侧）。
 *
 * 安全边界（对应 N5 规格）：
 * - 只允许 https；http 仅在 debug 且目标是本机/模拟器（localhost / 127.0.0.1 / 10.0.2.2）时放行；
 * - 禁止 URL 内嵌凭据与片段；
 * - 禁止重定向（3xx 一律失败，避免跳转到内部地址绕过校验）；
 * - 解析主机到 IP 并拒绝环回、私网、链路本地、CGNAT、组播及云元数据地址（SSRF）；
 * - **校验过的地址就是实际拨号的地址**（F6）：连接只使用 [UrlSafety.resolve] 返回的 IP 集合，域名只用于 TLS 的
 *   SNI 与主机名校验；不在连接阶段再次解析，DNS 重绑定无法把已校验的公网地址换成内网地址；
 * - 强制响应大小上限与读写超时；取消/总超时会关闭底层 socket，慢速分块不能无限拖延。
 *
 * 通过 [HostResolver] 注入解析结果，使 SSRF 判定可在 JVM 单测里确定性验证；通过 [AddressDialer] 注入拨号动作，
 * 使“检查时公网、连接时私网”的重绑定场景可被观测（默认实现是真实 TCP connect）。
 */

fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

object SystemHostResolver : HostResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

/** 实际拨号动作；测试用它记录/阻止连接，生产实现是真实 TCP connect。 */
fun interface AddressDialer {
    fun dial(address: InetAddress, port: Int, timeoutMillis: Int): Socket
}

object DefaultAddressDialer : AddressDialer {
    override fun dial(address: InetAddress, port: Int, timeoutMillis: Int): Socket =
        Socket().apply { connect(InetSocketAddress(address, port), timeoutMillis) }
}

object UrlSafety {
    private val DEBUG_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private val METADATA_HOSTS = setOf("metadata", "metadata.google.internal", "169.254.169.254", "instance-data")

    fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            if (b0 == 0) return true                       // 0.0.0.0/8
            if (b0 == 100 && b1 in 64..127) return true    // 100.64.0.0/10 CGNAT
            if (b0 == 169 && b1 == 254) return true        // 169.254.0.0/16 link-local / metadata
            if (b0 == 127) return true                     // 127.0.0.0/8
            if (b0 == 10) return true                      // 10.0.0.0/8
            if (b0 == 172 && b1 in 16..31) return true     // 172.16.0.0/12
            if (b0 == 192 && b1 == 168) return true        // 192.168.0.0/16
        } else if (bytes.size == 16) {
            val b0 = bytes[0].toInt() and 0xff
            if ((b0 and 0xfe) == 0xfc) return true         // fc00::/7 unique local
            if (b0 == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80) return true // fe80::/10
            val v4Mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
                (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
            if (v4Mapped) return isBlockedAddress(InetAddress.getByAddress(byteArrayOf(bytes[12], bytes[13], bytes[14], bytes[15])))
        }
        return false
    }

    private fun literal(host: String): InetAddress? {
        val clean = host.removePrefix("[").removeSuffix("]")
        val ipv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(clean)
        val ipv6 = clean.contains(':')
        if (!ipv4 && !ipv6) return null
        return runCatching { InetAddress.getByName(clean) }.getOrNull()
    }

    /** A validated URL together with the exact addresses that may be dialed for it. */
    data class Target(val uri: URI, val addresses: List<InetAddress>, val debugHttp: Boolean)

    fun resolve(raw: String, debug: Boolean = false, resolver: HostResolver = SystemHostResolver): Target {
        val uri = runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("无效的来源地址") }
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("缺少协议")
        require(scheme == "https" || (debug && scheme == "http")) { "仅支持 HTTPS 来源" }
        require(uri.userInfo == null && uri.fragment == null) { "来源地址不得包含凭据或片段" }
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            ?: throw IllegalArgumentException("缺少主机名")
        require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
        if (scheme == "http") require(host in DEBUG_HTTP_HOSTS) { "Debug 仅允许本机/模拟器使用 HTTP" }
        require(host !in METADATA_HOSTS) { "禁止访问云元数据/内部地址" }
        val debugAllow = debug && host in DEBUG_HTTP_HOSTS && scheme == "http"
        val addresses = literal(host)?.let { listOf(it) } ?: resolver.resolve(host)
        require(addresses.isNotEmpty()) { "主机无法解析" }
        require(addresses.size <= 16) { "主机解析地址过多" }
        for (address in addresses) {
            if (debugAllow) {
                // Debug 显式放行本机/模拟器（也用于本地故障测试），其余仍按 SSRF 规则阻断。
                if (address.isLoopbackAddress || host == "10.0.2.2") continue
            }
            require(!isBlockedAddress(address)) { "禁止访问私有/环回/链路本地/元数据地址" }
        }
        return Target(uri, addresses, debugAllow)
    }

    fun check(raw: String, debug: Boolean = false, resolver: HostResolver = SystemHostResolver): URI =
        resolve(raw, debug, resolver).uri
}

class RestrictedFetcher(
    private val debug: Boolean = false,
    private val resolver: HostResolver = SystemHostResolver,
    private val defaultMaxBytes: Int = 262_144,
    private val defaultTimeoutMillis: Long = 12_000,
    private val dial: AddressDialer = DefaultAddressDialer,
) {
    data class Resource(val url: String, val body: String, val retrievedAtMillis: Long, val contentType: String?)

    /**
     * [headers] are only for the *configured service* request (e.g. a search API token). They are never logged, never
     * included in exception text, and cannot follow a redirect because 3xx responses are refused outright.
     */
    suspend fun fetch(
        rawUrl: String,
        maxBytes: Int = defaultMaxBytes,
        timeoutMillis: Long = defaultTimeoutMillis,
        headers: Map<String, String> = emptyMap(),
    ): Resource {
        require(maxBytes in 1..1_048_576) { "响应上限无效" }
        require(timeoutMillis in 100..60_000) { "超时设置无效" }
        require(headers.size <= 8 && headers.all { (name, value) ->
            name.matches(Regex("[A-Za-z0-9-]{1,64}")) &&
                value.none { it == '\r' || it == '\n' } && value.length <= 512
        }) { "请求头无效" }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val socketRef = java.util.concurrent.atomic.AtomicReference<Socket?>()
        // H1: cancellation (not only the deadline) must abort the active connect/TLS/read/write immediately. Registering
        // with `onCancelling = true` fires when the job is *cancelled*, not when the blocking body eventually returns.
        // A child coroutine suspended on awaitCancellation is cancelled *immediately* when this request is cancelled, so
        // its finally block closes the active socket without waiting for the blocking read to return.
        val cancelWatcher = CoroutineScope(currentCoroutineContext()).launch(
            start = CoroutineStart.UNDISPATCHED,
        ) {
            try {
                awaitCancellation()
            } finally {
                runCatching { socketRef.getAndSet(null)?.close() }
            }
        }
        // G6: an absolute deadline watchdog actively closes whichever socket is currently in connect/TLS/read/write. A
        // completion callback alone cannot interrupt a blocking read; a slow trickle would keep it alive past the budget.
        val watchdog = Thread {
            try {
                val sleep = (deadline - System.nanoTime()) / 1_000_000L
                if (sleep > 0) Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                return@Thread
            }
            runCatching { socketRef.getAndSet(null)?.close() }
        }.apply { isDaemon = true; start() }
        return try {
            withTimeout(timeoutMillis) {
                withContext(Dispatchers.IO) {
                    try {
                        fetchBlocking(rawUrl, maxBytes, timeoutMillis, deadline, headers, socketRef)
                    } finally {
                        runCatching { socketRef.getAndSet(null)?.close() }
                    }
                }
            }
        } finally {
            watchdog.interrupt()
            cancelWatcher.cancel()
            runCatching { socketRef.getAndSet(null)?.close() }
        }
    }

    private suspend fun fetchBlocking(
        rawUrl: String,
        maxBytes: Int,
        timeoutMillis: Long,
        deadline: Long,
        headers: Map<String, String>,
        socketRef: java.util.concurrent.atomic.AtomicReference<Socket?>,
    ): Resource {
        // F6: one resolution; the validated address set is what gets dialed. A later DNS answer cannot redirect the
        // connection to a private address because nothing resolves again after this point.
        val target = UrlSafety.resolve(rawUrl, debug, resolver)
        var lastError: Exception? = null
        for (address in target.addresses) {
            // A cancelled request must not start another address attempt.
            currentCoroutineContext().ensureActive()
            try {
                return exchange(target, address, maxBytes, timeoutMillis, deadline, headers, socketRef)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // I2: the cancellation watcher closes the socket, so the read fails with SocketException. If the request
                // was cancelled, that is cancellation — not a network error, not retried, never returned to the caller.
                currentCoroutineContext().ensureActive()
                lastError = e
            }
        }
        throw lastError ?: IllegalArgumentException("没有可连接的地址")
    }

    private fun exchange(
        target: UrlSafety.Target,
        address: InetAddress,
        maxBytes: Int,
        timeoutMillis: Long,
        deadline: Long,
        headers: Map<String, String>,
        socketRef: java.util.concurrent.atomic.AtomicReference<Socket?>,
    ): Resource {
        fun remainingMillis(): Long {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            require(left > 0) { "总超时已到" }
            return left
        }
        val uri = target.uri
        val https = uri.scheme.equals("https", ignoreCase = true)
        val port = if (uri.port != -1) uri.port else if (https) 443 else 80
        val rawSocket = dial.dial(address, port, minOf(remainingMillis(), 8_000L).toInt())
        socketRef.set(rawSocket)   // registered BEFORE TLS so a deadline can abort the handshake too
        val socket = try {
            if (!https) rawSocket else {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(rawSocket, uri.host, port, true) as SSLSocket
                // Keep certificate validation AND hostname verification against the URL host (never trust-all, never
                // "replace host with IP"): SNI is taken from uri.host by createSocket.
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.soTimeout = remainingMillis().toInt()
                ssl.startHandshake()
                socketRef.set(ssl)
                ssl
            }
        } catch (e: Exception) {
            runCatching { rawSocket.close() }
            throw e
        }
        socket.soTimeout = remainingMillis().toInt()
        try {
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val hostHeader = when {
                uri.port == -1 || (https && port == 443) || (!https && port == 80) -> uri.host
                else -> "${uri.host}:$port"
            }
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                    append("Accept: text/html,application/json;q=0.9\r\n")
                }
                append("User-Agent: SubTrack/1.4 (restricted-fetch)\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }

            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readLine(input, 8 * 1024) ?: throw IllegalStateException("响应缺少状态行")
            require(statusLine.startsWith("HTTP/1.")) { "响应状态行无效" }
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IllegalStateException("响应状态码无效")
            if (code in 300..399) throw IllegalArgumentException("禁止重定向（HTTP $code）")
            require(code in 200..299) { "抓取失败 HTTP $code" }

            var contentLength = -1L
            var chunked = false
            var contentType: String? = null
            var headerBytes = 0
            while (true) {
                val line = readLine(input) ?: throw IllegalStateException("响应头不完整")
                headerBytes += line.length + 2
                require(headerBytes <= 64 * 1024) { "响应头过大" }
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (name) {
                    "content-length" -> contentLength = value.toLongOrNull() ?: throw IllegalStateException("Content-Length 无效")
                    "transfer-encoding" -> if (value.contains("chunked", ignoreCase = true)) chunked = true
                    "content-type" -> contentType = value
                }
            }
            if (contentLength > maxBytes) throw IllegalArgumentException("响应超过上限")
            val body = if (chunked) readChunked(input, maxBytes, deadline) else readFixed(input, contentLength, maxBytes, deadline)
            return Resource(uri.toString(), body.toString(Charsets.UTF_8), System.currentTimeMillis(), contentType)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readLine(input: InputStream, maxLength: Int = 8 * 1024): String? {
        val out = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val next = input.read()
            if (next < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (next == '\n'.code) {
                val bytes = out.toByteArray()
                val length = if (previous == '\r'.code) bytes.size - 1 else bytes.size
                return String(bytes, 0, length.coerceAtLeast(0), Charsets.ISO_8859_1)
            }
            require(out.size() <= maxLength) { "响应行过长" }
            out.write(next)
            previous = next
        }
    }

    private fun readFixed(input: InputStream, contentLength: Long, maxBytes: Int, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = if (contentLength >= 0) contentLength else Long.MAX_VALUE
        while (remaining > 0) {
            require((deadline - System.nanoTime()) > 0) { "总超时已到" }
            val limited = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, limited)
            if (n < 0) {
                // G6: a declared Content-Length that is not fully delivered is a truncated response, never a success.
                require(contentLength < 0) { "响应不完整（声明 $contentLength 字节，实际 ${out.size()}）" }
                break
            }
            require(out.size() + n <= maxBytes) { "响应超过上限" }
            out.write(buffer, 0, n)
            if (contentLength >= 0) remaining -= n
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream, maxBytes: Int, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var trailerBytes = 0
        while (true) {
            require((deadline - System.nanoTime()) > 0) { "总超时已到" }
            val header = readLine(input, 1024) ?: throw IllegalStateException("分块响应不完整")
            val size = header.substringBefore(';').trim().toIntOrNull(16) ?: throw IllegalStateException("分块长度无效")
            if (size == 0) {
                // consume trailers
                while (true) {
                    val line = readLine(input, 1024) ?: throw IllegalStateException("分块尾部不完整")
                    trailerBytes += line.length + 2
                    require(trailerBytes <= 16 * 1024) { "分块尾部过大" }
                    if (line.isEmpty()) break
                }
                return out.toByteArray()
            }
            require(out.size() + size <= maxBytes) { "响应超过上限" }
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(chunk, read, size - read)
                if (n < 0) throw IllegalStateException("分块响应不完整")
                read += n
            }
            out.write(chunk)
            readLine(input) // trailing CRLF
        }
    }
}
