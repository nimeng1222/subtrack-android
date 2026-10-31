package com.subtrack.sync

import org.junit.Assert.*
import org.junit.Test

class SyncCoreTest {
    private val vault = "vault_123"
    private val op = "operation_0000000001"
    private fun version(id: String, clock: Map<String, Long>, deleted: Boolean = false) = CausalVersion(id, clock, deleted)
    private val a = version("operation_0000000001", mapOf("device_a" to 1))
    private val b = version("operation_0000000002", mapOf("device_b" to 1))

    @Test fun recovery_keys_and_ciphertexts_use_fresh_randomness_and_round_trip() {
        val recovery = SyncCrypto.generateRecoveryKey()
        assertNotEquals(recovery, SyncCrypto.generateRecoveryKey())
        val key = SyncCrypto.parseRecoveryKey(recovery)
        val one = SyncCrypto.encrypt(key, vault, op, 1, "offline ledger".toByteArray())
        val two = SyncCrypto.encrypt(key, vault, op, 1, "offline ledger".toByteArray())
        assertFalse(one.nonce.contentEquals(two.nonce))
        assertArrayEquals("offline ledger".toByteArray(), SyncCrypto.decrypt(key, vault, op, one))
    }

    @Test fun authentication_rejects_tamper_wrong_vault_operation_epoch_and_key() {
        val key = SyncCrypto.parseRecoveryKey(SyncCrypto.generateRecoveryKey())
        val envelope = SyncCrypto.encrypt(key, vault, op, 1, "secret".toByteArray())
        fun rejects(block: () -> Unit) { assertNotNull(runCatching(block).exceptionOrNull()) }
        rejects { SyncCrypto.decrypt(key, "vault_other", op, envelope) }
        rejects { SyncCrypto.decrypt(key, vault, "operation_0000000002", envelope) }
        rejects { SyncCrypto.decrypt(key, vault, op, envelope.copy(keyEpoch = 2)) }
        rejects { SyncCrypto.decrypt(ByteArray(32), vault, op, envelope) }
        val changed = envelope.ciphertext.clone(); changed[0] = (changed[0].toInt() xor 1).toByte()
        rejects { SyncCrypto.decrypt(key, vault, op, envelope.copy(ciphertext = changed)) }
    }

    @Test fun recovery_and_payload_limits_are_enforced() {
        assertNotNull(runCatching { SyncCrypto.parseRecoveryKey("not-a-recovery-key") }.exceptionOrNull())
        assertNotNull(runCatching { SyncCrypto.encrypt(ByteArray(32), vault, op, 1, ByteArray(49 * 1024)) }.exceptionOrNull())
        assertNotNull(runCatching { SyncCrypto.encrypt(ByteArray(32), vault, op, 0, byteArrayOf()) }.exceptionOrNull())
    }

    @Test fun concurrent_heads_survive_and_explicit_resolution_covers_both() {
        val heads = Causality.integrate(listOf(a), b)
        assertEquals(setOf(a.id, b.id), heads.map { it.id }.toSet())
        val merged = version("operation_0000000003", Causality.nextClock(heads, "device_a"))
        assertEquals(3, Causality.integrate(heads, merged).size)
        assertEquals(listOf(merged), Causality.integrate(heads, merged, setOf(a.id, b.id)))
        // Semantic adjustment (F1): a late device may not have received every version a resolution cites. Citations of
        // versions this device does not know are vacuously satisfied (they arrive later and are ignored as older than
        // the resolution). Citations of versions it DOES know but which are not causally covered must still be rejected.
        assertNull(runCatching { Causality.integrate(heads, merged, setOf("operation_0000000099")) }.exceptionOrNull())
        assertNotNull(
            "a known head that is not causally covered may not be cited as resolved",
            runCatching { Causality.integrate(listOf(a), b, setOf(a.id)) }.exceptionOrNull(),
        )
    }

    @Test fun concurrent_delete_and_old_edit_never_silently_resurrect() {
        val deleted = version(a.id, a.clock, deleted = true)
        val heads = Causality.integrate(listOf(deleted), b)
        assertEquals(2, heads.size)
        val attemptedResurrection = version("operation_0000000003", Causality.nextClock(listOf(deleted), "device_a"))
        assertEquals(setOf(deleted.id, attemptedResurrection.id), Causality.integrate(listOf(deleted), attemptedResurrection).map { it.id }.toSet())
        assertEquals(listOf(attemptedResurrection), Causality.integrate(listOf(deleted), attemptedResurrection, setOf(deleted.id)))
        assertEquals(listOf(deleted), Causality.integrate(listOf(deleted), version("operation_0000000004", mapOf("device_a" to 1), true).copy(id = deleted.id)))
    }

    @Test fun replay_and_equal_clock_with_different_ids_are_not_new_changes() {
        assertEquals(listOf(a), Causality.integrate(listOf(a), a))
        val changedId = version("operation_0000000004", a.clock)
        assertNotNull(runCatching { Causality.integrate(listOf(a), changedId) }.exceptionOrNull())
        assertEquals(CausalRelation.CONCURRENT, Causality.compare(a.clock, b.clock))
    }
}
