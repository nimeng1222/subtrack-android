package com.subtrack.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ciphertext is excluded from all Android and application backups. Key never leaves Keystore. */
class SecretStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "network-key.bin")
    private val alias = "subtrack.network.byok.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(value: String) = putBound("legacy-test-only", value)
    @Synchronized fun putBound(endpoint: String, value: String) {
        if (value.isBlank()) { clear(); return }
        require(value.length <= 4096 && !value.contains('\n') && !value.contains('\r'))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (_: java.security.GeneralSecurityException) {
            // Keystore 里的旧密钥被系统判废（换锁屏方式、设备迁移等）时旧密文本来也解不开：
            // 删掉重建一把，让用户还能继续保存新的 Key，而不是永远"保存失败"。
            clear()
            cipher.init(Cipher.ENCRYPT_MODE, key())
        }
        val temp = File(file.parentFile, "network-key.tmp")
        temp.writeBytes(cipher.iv + cipher.doFinal((endpoint + "\n" + value).toByteArray(Charsets.UTF_8)))
        check(temp.renameTo(file))
    }
    @Synchronized fun get(): String? = getBound("legacy-test-only")
    @Synchronized fun getBound(endpoint: String): String? {
        if (!file.exists()) return null
        // clear() 可能在 exists() 与读取之间删掉文件：那等价于"没有密钥"，不是错误。
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size !in 29..8192) return null
        // 密文解不开（密钥被判废/文件被替换）＝没有可用密钥，不是崩溃条件。
        val clear = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        val split = clear.indexOf('\n')
        return if (split >= 0 && clear.substring(0, split) == endpoint) clear.substring(split + 1) else null
    }
    @Synchronized fun clear() { file.delete(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
}
