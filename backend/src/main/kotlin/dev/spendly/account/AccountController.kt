package dev.spendly.account

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "accounts")
class AccountController(private val service: AccountService) {

    @GetMapping
    fun listAccounts(): List<AccountResponse> = service.list().map(AccountResponse::of)

    @PostMapping
    fun createAccount(@Valid @RequestBody body: AccountCreate): ResponseEntity<AccountResponse> {
        val created = service.create(body)
        return ResponseEntity.created(URI.create("/api/accounts/${created.id}")).body(AccountResponse.of(created))
    }

    @GetMapping("/{accountId}")
    fun getAccount(@PathVariable accountId: UUID): AccountResponse = AccountResponse.of(service.get(accountId))

    @PatchMapping("/{accountId}")
    fun updateAccount(@PathVariable accountId: UUID, @Valid @RequestBody body: AccountUpdate): AccountResponse =
        AccountResponse.of(service.update(accountId, body))

    @DeleteMapping("/{accountId}")
    fun deleteAccount(@PathVariable accountId: UUID): ResponseEntity<Void> {
        service.delete(accountId)
        return ResponseEntity.noContent().build()
    }
}
