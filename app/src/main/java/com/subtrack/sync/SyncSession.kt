package com.subtrack.sync

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * B/G5: an immutable sync session. endpoint/vault/device/keyProvider are frozen when the session is opened; background
 * work started under it can never read a newer "active vault" and encrypt into it. Switching vaults/endpoints replaces
 * this object, which invalidates every old task through [SwitchableSyncWriteGateway.isCurrent].
 */
data class SyncSession(
    val endpoint: String,
    val vaultId: String,
    val deviceId: String,
    /** Bearer token for this vault's device; kept only in memory, never logged. */
    val token: String,
    val keyProvider: suspend (Int) -> ByteArray,
)

class SyncSessionLostException(message: String = "同步会话已切换，拒绝提交旧请求") : IllegalStateException(message)

/**
 * C/G4: a remote operation was encrypted under a key epoch this device does not hold yet. That is NOT corruption: the
 * recovery bundle for that epoch must be imported out of band first. The checkpoint must stay put so nothing is skipped.
 */
class MissingEpochKeyException(val epoch: Int) : IllegalStateException("缺少代次 $epoch 的同步密钥，请先导入该代次的恢复包")

/** One lock per vault, shared by every entry point (write gateway, syncNow, import, rotation, disable/switch). */
object SyncVaultLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun forVault(vaultId: String): Mutex = locks.getOrPut(vaultId) { Mutex() }
}
