package com.subtrack.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** E4/N3 update-manifest hardening: reject paths not covered by NetworkTest, using a real RSA signature envelope. */
class UpdateHardeningTest {
    private fun signed(payload: String): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes = payload.toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(bytes) }.sign()
        val b64 = Base64.getEncoder()
        return """{"payload":"${b64.encodeToString(bytes)}","signature":"${b64.encodeToString(sig)}"}""" to
            b64.encodeToString(pair.public.encoded)
    }

    private fun manifest(
        channel: String = "direct",
        versionCode: Int = 19,
        minSdk: Int = 26,
        size: Long = 100,
        sha: String = "a".repeat(64),
        url: String = "https://example.com/app.apk",
        includeSha: Boolean = true,
    ): String {
        val shaField = if (includeSha) ""","sha256":"$sha"""" else ""
        return """{"packageName":"com.subtrack","channel":"$channel","versionName":"1.4.1","versionCode":$versionCode,"minSdk":$minSdk,"downloadUrl":"$url","sizeBytes":$size$shaField}"""
    }

    private fun rejects(payload: String) {
        val (e, k) = signed(payload)
        assertNotNull("must reject: $payload", runCatching { UpdateVerifier.decode(e, k, 18, 35, "direct") }.exceptionOrNull())
    }

    @Test fun channel_mismatch_is_rejected() = rejects(manifest(channel = "beta"))

    @Test fun non_https_download_url_is_rejected() = rejects(manifest(url = "http://example.com/app.apk"))

    @Test fun malformed_sha256_is_rejected() {
        rejects(manifest(sha = "A".repeat(64)))   // uppercase not allowed
        rejects(manifest(sha = "abc"))            // too short
        rejects(manifest(includeSha = false))     // missing
    }

    @Test fun nonpositive_version_zero_size_and_high_minsdk_are_rejected() {
        rejects(manifest(versionCode = 0))
        rejects(manifest(size = 0))
        rejects(manifest(size = UpdateVerifier.MAX_APK + 1))
        rejects(manifest(minSdk = 99))
    }

    @Test fun min_sdk_equal_to_running_sdk_is_accepted() {
        val (e, k) = signed(manifest(minSdk = 35))
        assertEquals(19L, UpdateVerifier.decode(e, k, 18, 35, "direct").versionCode)
    }
}
