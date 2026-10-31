package com.subtrack.network

import com.subtrack.data.backup.*
import com.subtrack.domain.model.UpdateManifest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object UpdateVerifier {
    const val MAX_APK = 64L * 1024 * 1024
    fun decode(envelope: String, trustedKey: String, currentCode: Long, sdk: Int, channel: String): UpdateManifest {
        require(trustedKey.isNotBlank()) { "无可信发布配置：尚未固定正式公钥" }
        val e = parseObject(envelope)
        val payload = Base64.getDecoder().decode(e.text("payload") ?: error("缺少签名清单"))
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(trustedKey)))
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update(payload); verify(Base64.getDecoder().decode(e.text("signature")))
        }) { "发布清单签名无效" }
        val o = parseObject(payload.toString(Charsets.UTF_8))
        require(o.text("packageName") == "com.subtrack" && o.text("channel") == channel) { "发布包名或渠道不匹配" }
        val minSdk = o.number("minSdk") ?: error("缺少 minSdk")
        require(minSdk in 26..sdk.toLong()) { "新版本不支持当前系统" }
        val code = o.number("versionCode") ?: error("缺少版本码")
        require(code > 0)
        val size = o.number("sizeBytes") ?: error("缺少大小")
        require(size in 1..MAX_APK)
        val url = o.text("downloadUrl") ?: error("缺少下载入口")
        NetworkUrls.validate(url)
        val hash = o.text("sha256") ?: error("缺少哈希")
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return UpdateManifest("com.subtrack", channel, o.text("versionName") ?: error("缺少版本名"), code,
            minSdk.toInt(), url, size, hash, o.text("releaseNotes"), o.text("publishedAt"))
    }
}
