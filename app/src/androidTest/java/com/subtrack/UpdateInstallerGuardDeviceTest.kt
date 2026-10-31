package com.subtrack

import android.content.Intent
import com.subtrack.network.NetworkTransport
import com.subtrack.network.UpdateInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E4/N3 installer guard + explicit-install intent (device): only a candidate inside cache/updates is installable, and
 * the installer action is an explicit ACTION_VIEW package-archive Intent routed to the system installer (user confirms)
 * — never a silent install.
 */
class UpdateInstallerGuardDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private fun installer() = UpdateInstaller(context, NetworkTransport(true))

    @Test fun install_intent_is_explicit_system_installer_inside_updates_dir() {
        val updates = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(updates, "candidate-test.apk").apply { writeBytes(ByteArray(16)) }
        try {
            val i = installer().installIntent(file)
            assertEquals(Intent.ACTION_VIEW, i.action)
            assertEquals("application/vnd.android.package-archive", i.type)
            assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals("content", i.data?.scheme)
            assertTrue(i.data.toString().contains(".updates"))
        } finally { file.delete() }
    }

    @Test fun rejects_files_outside_updates_dir_and_missing_files() {
        val outside = File(context.cacheDir, "evil.apk").apply { writeBytes(ByteArray(16)) }
        val missing = File(File(context.cacheDir, "updates"), "does-not-exist.apk")
        try {
            assertThrows(IllegalArgumentException::class.java) { installer().installIntent(outside) }
            assertThrows(IllegalArgumentException::class.java) { installer().installIntent(missing) }
        } finally { outside.delete() }
    }
}
