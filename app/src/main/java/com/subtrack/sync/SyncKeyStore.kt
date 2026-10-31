package com.subtrack.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The data key stays on device under an Android Keystore wrapping key; noBackup excludes ciphertext. */
class SyncKeyStore(private val context: Context) {
    private val alias = "subtrack.sync.wrap.v1"
    private fun file(vaultId: String, epoch: Int): AtomicFile {
        require(vaultId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && epoch > 0)
        val digest = MessageDigest.getInstance("SHA-256").digest(vaultId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.noBackupFilesDir, "sync-key-$digest-$epoch.bin"))
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun aad(vaultId: String, epoch: Int) = "SubTrack.Sync.Key.v1:$vaultId:$epoch".toByteArray(Charsets.UTF_8)

    @Synchronized fun store(vaultId: String, epoch: Int, recoveryMaterial: String) {
        val plain = SyncCrypto.parseRecoveryKey(recoveryMaterial)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad(vaultId, epoch)) }
            val ciphertext = cipher.iv + cipher.doFinal(plain)
            val target = file(vaultId, epoch); val out = target.startWrite()
            try { out.write(ciphertext); target.finishWrite(out) } catch (e: Exception) { target.failWrite(out); throw e }
        } finally { plain.fill(0) }
    }

    @Synchronized fun load(vaultId: String, epoch: Int): ByteArray? {
        val target = file(vaultId, epoch)
        if (!target.baseFile.exists()) return null
        val bytes = target.readFully(); require(bytes.size == 60)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(vaultId, epoch))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }

    @Synchronized fun remove(vaultId: String, epoch: Int) { file(vaultId, epoch).delete() }
}
