package dev.spendly.account

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AccountCreate(
    @field:NotBlank @field:Size(min = 1, max = 80)
    val label: String?,
    @field:NotBlank @field:Size(min = 1, max = 100)
    val bank: String?,
    @field:NotNull
    val type: AccountType?,
    @field:NotNull @field:Pattern(regexp = "^[A-Z]{3}$")
    val currency: String?,
    @field:NotBlank
    @Schema(description = "Full IBAN (spaces allowed). Validated with mod-97, then stored only as HMAC + last 4.", accessMode = Schema.AccessMode.WRITE_ONLY)
    val iban: String?,
    @field:Pattern(regexp = "^[0-9]{4}$")
    val cardLast4: String? = null,
    @field:NotNull
    val activeFrom: LocalDate?,
) {
    override fun toString() = "AccountCreate(label=$label, bank=$bank, type=$type, iban=<redacted>)"
}

/** PATCH body. Absent fields are left unchanged; `cardLast4` and `closedAt` may be set to null explicitly. */
class AccountUpdate {
    @field:Size(min = 1, max = 80)
    var label: String? = null

    @field:Size(min = 1, max = 100)
    var bank: String? = null

    @field:Pattern(regexp = "^[0-9]{4}$")
    var cardLast4: String? = null
        set(value) {
            field = value
            cardLast4Present = true
        }

    var closedAt: LocalDate? = null
        set(value) {
            field = value
            closedAtPresent = true
        }

    @JsonIgnore
    @Schema(hidden = true)
    var cardLast4Present: Boolean = false

    @JsonIgnore
    @Schema(hidden = true)
    var closedAtPresent: Boolean = false
}

data class AccountResponse(
    val id: UUID,
    val label: String,
    val bank: String,
    val type: AccountType,
    val currency: String,
    val ibanMasked: String,
    val cardLast4: String?,
    val activeFrom: LocalDate,
    val closedAt: LocalDate?,
    val createdAt: Instant,
) {
    companion object {
        fun of(a: AccountEntity) = AccountResponse(
            a.id, a.label, a.bank, a.type, a.currency, a.ibanMasked, a.cardLast4, a.activeFrom, a.closedAt, a.createdAt,
        )
    }
}
