package dev.spendly.shared.crypto

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for columns encrypted at rest (plan section 11, AC-SD-7).
 *
 * Format: `v1:` + base64(iv[12] || ciphertext || tag[16]). A fresh random IV per value, so the same
 * plaintext encrypts differently every time. The version prefix allows key rotation later.
 */
class AesGcmCipher(key: ByteArray) {
    private val keySpec: SecretKeySpec
    private val random = SecureRandom()

    init {
        require(key.size == KEY_BYTES) { "AES key must be exactly $KEY_BYTES bytes" }
        keySpec = SecretKeySpec(key.copyOf(), "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + sealed)
    }

    fun decrypt(value: String): String {
        require(value.startsWith(PREFIX)) { "Unsupported ciphertext format" }
        val bytes = Base64.getDecoder().decode(value.substring(PREFIX.length))
        require(bytes.size > IV_BYTES + TAG_BITS / 8) { "Ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        return String(plain, StandardCharsets.UTF_8)
    }

    override fun toString() = "AesGcmCipher(<key redacted>)"

    companion object {
        const val PREFIX = "v1:"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Decodes SPENDLY_AES_KEY (base64 of exactly 32 bytes); error messages never include the value. */
        fun fromBase64(base64Key: String): AesGcmCipher {
            val key = try {
                Base64.getDecoder().decode(base64Key.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("SPENDLY_AES_KEY is not valid base64")
            }
            check(key.size == KEY_BYTES) { "SPENDLY_AES_KEY must decode to exactly $KEY_BYTES bytes (got ${key.size})" }
            return AesGcmCipher(key)
        }
    }
}
