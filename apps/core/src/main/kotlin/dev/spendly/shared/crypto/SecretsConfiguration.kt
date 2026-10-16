package dev.spendly.shared.crypto

import dev.spendly.shared.config.SpendlyProperties
import java.nio.file.Path
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Loads the HMAC and AES keys at startup and fails fast with a clear message when either is missing
 * or invalid. The secret values are never logged or included in exception messages.
 */
@Configuration(proxyBeanMethods = false)
class SecretsConfiguration {

    @Bean
    fun hmacService(properties: SpendlyProperties): HmacService {
        val path = properties.secrets.hmacSecretPath?.trim().orEmpty()
        if (path.isEmpty()) {
            throw SecretsMisconfiguredException(
                "SPENDLY_HMAC_SECRET_PATH is not set. Point it at a file outside the repository that holds " +
                    "at least ${HmacService.MIN_KEY_BYTES} bytes of random text (see .env.example).",
            )
        }
        return try {
            HmacService.fromFile(Path.of(path))
        } catch (e: IllegalStateException) {
            throw SecretsMisconfiguredException(e.message ?: "Invalid HMAC key file")
        }
    }

    @Bean
    fun aesGcmCipher(properties: SpendlyProperties): AesGcmCipher {
        val key = properties.secrets.aesKey?.trim().orEmpty()
        if (key.isEmpty()) {
            throw SecretsMisconfiguredException(
                "SPENDLY_AES_KEY is not set. It must be the base64 encoding of exactly ${AesGcmCipher.KEY_BYTES} " +
                    "random bytes (see .env.example).",
            )
        }
        return try {
            AesGcmCipher.fromBase64(key)
        } catch (e: IllegalStateException) {
            throw SecretsMisconfiguredException(e.message ?: "Invalid SPENDLY_AES_KEY")
        }
    }
}

class SecretsMisconfiguredException(message: String) : IllegalStateException(message)
