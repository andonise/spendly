package dev.spendly.logging

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Logback safety net (plan section 11): code logs IDs only, but anything that slips through and looks like
 * an IBAN, a card number / CNP, an e-mail address or a phone number is masked before it is written.
 */
class MaskingMessageConverter : MessageConverter() {
    override fun convert(event: ILoggingEvent): String = mask(super.convert(event) ?: "")

    companion object {
        private val IBAN = Regex("(?<![A-Za-z0-9])[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?(?![A-Za-z0-9])")
        private val LONG_DIGITS = Regex("(?<![0-9A-Za-z-])[0-9](?:[ ]?[0-9]){12,18}(?![0-9A-Za-z-])")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
        private val PHONE = Regex("(?<![0-9A-Za-z+])(?:\\+40|0040|0)[ ]?7(?:[ ]?[0-9]){8}(?![0-9])")

        fun mask(message: String): String {
            if (message.isEmpty()) return message
            var m = IBAN.replace(message, "[IBAN]")
            m = LONG_DIGITS.replace(m, "[NUMBER]")
            m = EMAIL.replace(m, "[EMAIL]")
            m = PHONE.replace(m, "[PHONE]")
            return m
        }
    }
}
