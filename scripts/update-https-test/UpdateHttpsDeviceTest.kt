package com.subtrack

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.subtrack.network.NetworkTransport
import com.subtrack.network.UpdateInstaller
import com.subtrack.network.UpdateVerifier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Requires an explicitly configured, isolated TEST CA and separate TEST manifest verification key. */
class UpdateHttpsDeviceTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val base get() = requireNotNull(args.getString("updateFixtureBase")) { "Isolated updateFixtureBase is required" }
    private val trustedKey get() = requireNotNull(args.getString("updateFixturePublicKey")) { "Pinned TEST key is required" }
    private val context get() = Round2Instrumentation.instance.targetContext
    @Suppress("DEPRECATION")
    private val currentCode get() = context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }

    private suspend fun manifest(mode: String) = UpdateVerifier.decode(
        NetworkTransport(true).request("$base/manifest/$mode", throttle = false).toString(Charsets.UTF_8),
        trustedKey, currentCode, Build.VERSION.SDK_INT, "direct")

    @Test fun isolated_https_valid_manifest_and_same_certificate_apk_are_verified() = runBlocking {
        val m = manifest("valid")
        assertTrue("fixture candidate must be an upgrade", m.versionCode > currentCode)
        val file = UpdateInstaller(context, NetworkTransport(true)).download(m)
        try { assertTrue(file.exists()); assertEquals(m.sizeBytes, file.length()) }
        finally { file.delete() }
    }

    @Test fun isolated_https_bad_signature_and_modified_manifest_are_rejected() = runBlocking {
        for (mode in listOf("bad-signature", "tampered-manifest")) {
            val error = runCatching { manifest(mode) }.exceptionOrNull()
            assertNotNull("must reject $mode", error)
        }
    }

    @Test fun isolated_https_modified_apk_and_wrong_certificate_are_rejected() = runBlocking {
        for (mode in listOf("tampered-apk", "wrong-signer")) {
            val m = manifest(mode)
            val error = runCatching { UpdateInstaller(context, NetworkTransport(true)).download(m) }.exceptionOrNull()
            assertNotNull("must reject $mode", error)
            if (mode == "wrong-signer") assertTrue("fixture must reach certificate check", error!!.message.orEmpty().contains("证书"))
        }
    }

    @Test fun isolated_https_cancel_slow_manifest_and_download() = runBlocking {
        val manifestJob = async(Dispatchers.IO) { manifest("slow") }
        delay(250); manifestJob.cancelAndJoin()
        assertTrue(manifestJob.isCancelled)
        val m = manifest("slow-apk")
        val download = async(Dispatchers.IO) { UpdateInstaller(context, NetworkTransport(true)).download(m) }
        delay(250); download.cancelAndJoin()
        assertTrue(download.isCancelled)
    }
}
