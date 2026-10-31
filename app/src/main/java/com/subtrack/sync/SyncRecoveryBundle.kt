package com.subtrack.sync

/**
 * H2: versioned, vault+epoch-bound recovery bundle for the CLIENT data key.
 *
 * Format: `stb1:<vaultId>:<epoch>:<base64url data key>` (single line, no padding). It is deliberately *not* the server's
 * authentication recovery credential: importing this bundle never proves account ownership and never authenticates to the
 * server. Parsing is strict; a bundle whose vault or epoch does not match the active session is rejected before any write
 * (the caller must verify the binding BEFORE enabling sync writes).
 */
object SyncRecoveryBundle {
    private const val PREFIX = "stb1"
    private val VAULT = Regex("[A-Za-z0-9_-]{1,128}")

    data class Parsed(val vaultId: String, val epoch: Int, val keyMaterial: String)

    fun create(vaultId: String, epoch: Int, keyMaterial: String): String {
        require(vaultId.matches(VAULT) && epoch >= 1) { "恢复包绑定的账本/代次无效" }
        SyncCrypto.parseRecoveryKey(keyMaterial).fill(0)
        return "$PREFIX:$vaultId:$epoch:$keyMaterial"
    }

    fun parse(bundle: String): Parsed {
        require(bundle.length in 16..256 && !bundle.contains('\n') && !bundle.contains('\r')) { "恢复包格式无效" }
        val parts = bundle.trim().split(':')
        require(parts.size == 4 && parts[0] == PREFIX) { "恢复包版本不受支持" }
        val vault = parts[1]
        require(vault.matches(VAULT)) { "恢复包账本标识无效" }
        val epoch = parts[2].toIntOrNull() ?: throw IllegalArgumentException("恢复包代次无效")
        require(epoch in 1..1_000_000) { "恢复包代次超出范围" }
        SyncCrypto.parseRecoveryKey(parts[3]).fill(0)
        return Parsed(vault, epoch, parts[3])
    }
}
