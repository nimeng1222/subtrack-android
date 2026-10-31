package com.subtrack

import android.content.pm.Signature
import com.subtrack.network.UpdateInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4/N3 same-certificate signer policy (the core of "同证书覆盖升级"): the candidate APK must be signed by exactly the
 * set of certificates that signed the installed app — never trust-all, never empty, order-independent.
 */
class ApkSignerPolicyDeviceTest {
    private fun sig(vararg bytes: Int) = Signature(bytes.map { it.toByte() }.toByteArray())

    @Test fun requires_nonempty_identical_signer_sets() {
        val a = sig(1, 2, 3)
        val b = sig(4, 5, 6)
        assertTrue(UpdateInstaller.sameSignerSet(arrayOf(a), arrayOf(sig(1, 2, 3))))
        assertFalse(UpdateInstaller.sameSignerSet(arrayOf(a), arrayOf(b)))
        assertFalse(UpdateInstaller.sameSignerSet(null, arrayOf(a)))
        assertFalse(UpdateInstaller.sameSignerSet(arrayOf(a), emptyArray()))
        assertFalse(UpdateInstaller.sameSignerSet(emptyArray(), emptyArray()))
        // set semantics: order independent, and multi-signer must match exactly
        assertTrue(UpdateInstaller.sameSignerSet(arrayOf(a, b), arrayOf(b, a)))
        assertFalse(UpdateInstaller.sameSignerSet(arrayOf(a, b), arrayOf(a)))
    }
}
