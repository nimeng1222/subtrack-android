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

/** Device bearer credentials stay separate from the E2EE data key and all backups. */
class SyncCredentialStore(private val context: Context) {
    private val alias = "subtrack.sync.auth.wrap.v1"
    private fun target(tenant: String): AtomicFile {
        require(tenant.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val name = MessageDigest.getInstance("SHA-256").digest(tenant.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.noBackupFilesDir, "sync-auth-$name.bin"))
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun store(endpoint: String, credential: SyncDeviceCredential) {
        require(endpoint.length in 1..2048 && !endpoint.contains('\n'))
        require(credential.deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            credential.token.length in 32..200 && credential.token.none { it.isISOControl() })
        val raw = listOf(endpoint, credential.deviceId, credential.token).joinToString("\n").toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(credential.tenantId.toByteArray())
            val file = target(credential.tenantId); val out = file.startWrite()
            try { out.write(cipher.iv + cipher.doFinal(raw)); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
        } finally { raw.fill(0) }
    }
    @Synchronized fun load(endpoint: String, tenant: String): SyncDeviceCredential? {
        val file = target(tenant)
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully(); require(bytes.size in 29..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(tenant.toByteArray())
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try {
            val fields = plain.toString(Charsets.UTF_8).split('\n')
            require(fields.size == 3)
            return if (fields[0] == endpoint) SyncDeviceCredential(tenant, fields[1], fields[2]) else null
        } finally { plain.fill(0) }
    }
    @Synchronized fun remove(tenant: String) { target(tenant).delete() }
}
