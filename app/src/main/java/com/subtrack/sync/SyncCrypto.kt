package com.subtrack.sync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Recovery material is a random 256-bit data key, never the server's authentication token. */
object SyncCrypto {
    private const val MAX_PLAINTEXT = 48 * 1024
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    data class Envelope(val version: Int = 1, val keyEpoch: Int, val nonce: ByteArray, val ciphertext: ByteArray)

    fun generateRecoveryKey(): String = ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)

    fun parseRecoveryKey(material: String): ByteArray {
        require(material.length == 43 && material.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid recovery material" }
        val bytes = decoder.decode(material)
        require(bytes.size == 32 && encoder.encodeToString(bytes) == material) { "Invalid recovery material" }
        return bytes
    }

    private fun aad(vault: String, operationId: String, epoch: Int): ByteArray {
        require(vault.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(operationId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        require(epoch > 0)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { it.writeUTF("SubTrack.Sync.v1"); it.writeUTF(vault); it.writeUTF(operationId); it.writeInt(epoch) }
        }.toByteArray()
    }

    fun encrypt(key: ByteArray, vault: String, operationId: String, epoch: Int, plaintext: ByteArray): Envelope {
        require(key.size == 32 && plaintext.size <= MAX_PLAINTEXT)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(vault, operationId, epoch))
        return Envelope(keyEpoch = epoch, nonce = nonce, ciphertext = cipher.doFinal(plaintext))
    }

    fun decrypt(key: ByteArray, vault: String, operationId: String, envelope: Envelope): ByteArray {
        require(key.size == 32 && envelope.version == 1 && envelope.nonce.size == 12 &&
            envelope.ciphertext.size in 16..MAX_PLAINTEXT + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, envelope.nonce))
        cipher.updateAAD(aad(vault, operationId, envelope.keyEpoch))
        return cipher.doFinal(envelope.ciphertext)
    }
}
