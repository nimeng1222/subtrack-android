package com.subtrack.sync

/**
 * Future-key (epoch) rotation primitive. Generates a fresh 256-bit E2EE data key for the NEXT epoch and stores it in the
 * device KeyStore alongside the still-retained previous epoch key. After the caller advances `sync_state.keyEpoch`,
 * newly produced operations encrypt under the new epoch while operations previously received under the old epoch remain
 * decryptable (the old key is retained until an explicit cleanup). The new recovery material must be delivered to other
 * devices OUT OF BAND — the server never holds any key; that distribution UI/flow is out of this primitive's scope.
 */
object SyncKeyRotation {
    data class Prepared(val newEpoch: Int, val recoveryMaterial: String)

    fun prepare(vaultId: String, currentEpoch: Int, keyStore: SyncKeyStore): Prepared {
        require(currentEpoch >= 1) { "invalid epoch" }
        val newEpoch = currentEpoch + 1
        require(newEpoch > 0) { "epoch overflow" }
        val material = SyncCrypto.generateRecoveryKey()
        keyStore.store(vaultId, newEpoch, material)
        return Prepared(newEpoch, material)
    }
}
