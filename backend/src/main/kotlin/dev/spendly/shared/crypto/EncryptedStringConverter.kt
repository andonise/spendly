package dev.spendly.shared.crypto

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.springframework.stereotype.Component

/**
 * JPA converter that stores a String column as AES-256-GCM ciphertext (AC-SD-7).
 * Hibernate obtains it from Spring (SpringBeanContainer), so the key comes from [AesGcmCipher].
 */
@Component
@Converter(autoApply = false)
class EncryptedStringConverter(private val cipher: AesGcmCipher) : AttributeConverter<String?, String?> {
    override fun convertToDatabaseColumn(attribute: String?): String? = attribute?.let(cipher::encrypt)

    override fun convertToEntityAttribute(dbData: String?): String? = dbData?.let(cipher::decrypt)
}
