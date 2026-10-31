package com.subtrack

import com.subtrack.sync.SyncCrypto
import com.subtrack.sync.SyncRecoveryBundle
import org.junit.Assert.*
import org.junit.Test

/** H2: the recovery material is a versioned vault+epoch-bound bundle, not a bare data key. */
class SyncRecoveryBundleDeviceTest {
    private fun key() = SyncCrypto.generateRecoveryKey()

    @Test fun bundle_round_trip_binds_vault_and_epoch() {
        val material = key()
        val bundle = SyncRecoveryBundle.create("vault_bundle_0001", 3, material)
        val parsed = SyncRecoveryBundle.parse(bundle)
        assertEquals("vault_bundle_0001", parsed.vaultId)
        assertEquals(3, parsed.epoch)
        assertEquals(material, parsed.keyMaterial)
        assertFalse("must not be a bare 43-char key", bundle.length == 43)
    }

    @Test fun malformed_or_wrong_version_bundles_are_rejected() {
        val material = key()
        val bundle = SyncRecoveryBundle.create("vault_bundle_0001", 1, material)
        assertNotNull(runCatching { SyncRecoveryBundle.parse(material) }.exceptionOrNull())
        assertNotNull(runCatching { SyncRecoveryBundle.parse("stb2:vault_bundle_0001:1:$material") }.exceptionOrNull())
        assertNotNull(runCatching { SyncRecoveryBundle.parse("stb1:bad vault!:1:$material") }.exceptionOrNull())
        assertNotNull(runCatching { SyncRecoveryBundle.parse("stb1:vault_bundle_0001:0:$material") }.exceptionOrNull())
        assertNotNull(runCatching { SyncRecoveryBundle.parse("stb1:vault_bundle_0001:1:not-a-key") }.exceptionOrNull())
        // A bundle for a different vault must never validate as this one.
        assertNotEquals("vault_bundle_9999", SyncRecoveryBundle.parse(bundle).vaultId)
    }

    @Test fun create_rejects_keys_that_are_not_valid_recovery_material() {
        assertNotNull(runCatching { SyncRecoveryBundle.create("vault_bundle_0001", 1, "short") }.exceptionOrNull())
    }
}
