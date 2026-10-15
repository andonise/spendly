package dev.spendly.crypto

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed pseudonyms: HMAC-SHA256(key, "<namespace>:<value>") as 64-char lowercase hex (plan section 11).
 * The key is the trimmed UTF-8 content of the file at SPENDLY_HMAC_SECRET_PATH.
 */
class HmacService(key: ByteArray) {
    private val keySpec: SecretKeySpec

    init {
        require(key.isNotEmpty()) { "HMAC key must not be empty" }
        keySpec = SecretKeySpec(key.copyOf(), ALGORITHM)
    }

    fun hex(message: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    /** Same token as the sanitizer's `iban` rule: HMAC("iban:" + compactIban). */
    fun ibanToken(compactIban: String): String = hex("iban:$compactIban")

    override fun toString() = "HmacService(<key redacted>)"

    companion object {
        const val ALGORITHM = "HmacSHA256"
        const val MIN_KEY_BYTES = 32

        fun fromSecretText(secret: String): HmacService = HmacService(secret.trim().toByteArray(StandardCharsets.UTF_8))

        /** Reads and validates the key file; error messages never include the key. */
        fun fromFile(path: Path): HmacService {
            check(Files.isRegularFile(path)) { "SPENDLY_HMAC_SECRET_PATH does not point to a readable file: $path" }
            val key = Files.readString(path, StandardCharsets.UTF_8).trim().toByteArray(StandardCharsets.UTF_8)
            check(key.size >= MIN_KEY_BYTES) {
                "The HMAC key file at $path is too short (${key.size} bytes after trimming; need at least $MIN_KEY_BYTES)"
            }
            return HmacService(key)
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}
