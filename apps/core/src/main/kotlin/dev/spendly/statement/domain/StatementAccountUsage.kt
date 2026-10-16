package dev.spendly.statement.domain

import dev.spendly.account.AccountUsage
import dev.spendly.statement.persistence.StatementRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class StatementAccountUsage(private val statements: StatementRepository) : AccountUsage {
    override fun statementCount(accountId: UUID): Long = statements.countByAccount(accountId)
}
