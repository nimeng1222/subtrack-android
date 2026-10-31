package com.subtrack

import com.subtrack.sync.SyncCrypto
import com.subtrack.sync.SyncKeyRotation
import com.subtrack.sync.SyncKeyStore
import org.junit.Assert.*
import org.junit.Test

/**
 * N4 key rotation primitive (device, real AndroidKeyStore): rotating to a new epoch stores a fresh data key, keeps the
 * previous epoch key available for decrypting already-received operations, and the new material differs from the old.
 * (Distribution of the new material to other devices is out-of-band and out of scope here; the server never holds keys.)
 */
class SyncKeyRotationDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext

    @Test fun rotation_adds_new_epoch_key_and_retains_old() {
        val vault = "vault_rotate_test"
        val store = SyncKeyStore(context)
        val m1 = SyncCrypto.generateRecoveryKey()
        store.store(vault, 1, m1)
        try {
            val prepared = SyncKeyRotation.prepare(vault, 1, store)
            assertEquals(2, prepared.newEpoch)
            assertNotEquals(m1, prepared.recoveryMaterial)

            val k1 = store.load(vault, 1)!!
            val k2 = store.load(vault, 2)!!
            assertArrayEquals(SyncCrypto.parseRecoveryKey(m1), k1)                 // old epoch key retained
            assertArrayEquals(SyncCrypto.parseRecoveryKey(prepared.recoveryMaterial), k2) // new epoch key stored
            assertFalse(k1.contentEquals(k2))

            // Ciphertext under the new epoch decrypts with the new key; an old-epoch ciphertext still decrypts with old.
            val opId = "operation_rotate000001"
            val envNew = SyncCrypto.encrypt(k2, vault, opId, 2, "new".toByteArray())
            assertArrayEquals("new".toByteArray(), SyncCrypto.decrypt(k2, vault, opId, envNew))
            val envOld = SyncCrypto.encrypt(k1, vault, opId, 1, "old".toByteArray())
            assertArrayEquals("old".toByteArray(), SyncCrypto.decrypt(store.load(vault, 1)!!, vault, opId, envOld))
        } finally {
            store.remove(vault, 1); store.remove(vault, 2)
        }
    }
}
