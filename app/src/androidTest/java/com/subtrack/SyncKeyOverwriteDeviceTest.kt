package com.subtrack

import com.subtrack.sync.SyncCrypto
import com.subtrack.sync.SyncKeyStore
import com.subtrack.sync.SyncProductService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** I1: a (vault, epoch) key is write-once; same key is idempotent and a different key is rejected with no side effects. */
class SyncKeyOverwriteDeviceTest {
    private val context get() = Round2Instrumentation.instance.targetContext
    private fun service(): SyncProductService =
        (context.applicationContext as SubTrackApp).container.syncService
    private fun store() = SyncKeyStore(context)

    @Test fun same_epoch_different_key_is_rejected_and_old_key_still_decrypts() = runBlocking {
        val vault = "vault_i1_key_" + System.nanoTime()
        val epoch = 1
        val keyA = SyncCrypto.generateRecoveryKey()
        val keyB = SyncCrypto.generateRecoveryKey()
        assertTrue(service().storeKeyChecked(vault, epoch, keyA))
        // Encrypt real ciphertext under key A (the operation payload shape).
        val envelope = SyncCrypto.encrypt(
            SyncCrypto.parseRecoveryKey(keyA), "vault$vault", "op_i1_000000000001", epoch, "ledger payload".toByteArray(),
        )
        val rejection = runCatching { runBlocking { service().storeKeyChecked(vault, epoch, keyB) } }.exceptionOrNull()
        assertTrue("a different key for the same epoch must be rejected", rejection is IllegalArgumentException)
        assertArrayEquals("the original key file must be untouched", SyncCrypto.parseRecoveryKey(keyA), store().load(vault, epoch))
        assertEquals("ciphertext made under the original key still decrypts", "ledger payload",
            SyncCrypto.decrypt(SyncCrypto.parseRecoveryKey(keyA), "vault$vault", "op_i1_000000000001", envelope)
                .toString(Charsets.UTF_8))
    }

    @Test fun same_key_import_is_idempotent() = runBlocking {
        val vault = "vault_i1_idem_" + System.nanoTime()
        val key = SyncCrypto.generateRecoveryKey()
        assertTrue(service().storeKeyChecked(vault, 2, key))
        assertFalse("storing the identical key again is a successful no-op", service().storeKeyChecked(vault, 2, key))
        assertArrayEquals(SyncCrypto.parseRecoveryKey(key), store().load(vault, 2))
    }

    @Test fun rejected_import_leaves_no_partial_key() = runBlocking {
        val vault = "vault_i1_partial_" + System.nanoTime()
        val keyA = SyncCrypto.generateRecoveryKey()
        service().storeKeyChecked(vault, 1, keyA)
        runCatching { service().storeKeyChecked(vault, 1, SyncCrypto.generateRecoveryKey()) }
        // Epoch 2 was never written by the failed import attempt.
        assertNull(store().load(vault, 2))
        assertArrayEquals(SyncCrypto.parseRecoveryKey(keyA), store().load(vault, 1))
    }
}
