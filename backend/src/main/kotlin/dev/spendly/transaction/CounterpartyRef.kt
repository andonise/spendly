package dev.spendly.transaction

import java.sql.ResultSet
import java.util.UUID

/** contracts/openapi-draft.yaml `CounterpartyRef`. */
data class CounterpartyRef(
    val token: String,
    val kind: String?,
    val label: String?,
    val isOwnAccount: Boolean,
    val ownAccountId: UUID?,
) {
    companion object {
        /** Reads columns counterparty_token, counterparty_token_kind, user_label, is_own_account, own_account_id. */
        fun from(rs: ResultSet): CounterpartyRef? {
            val token = rs.getString("counterparty_token") ?: return null
            val ownAccountId = rs.getObject("own_account_id", UUID::class.java)
            return CounterpartyRef(
                token = token,
                kind = rs.getString("counterparty_token_kind"),
                label = rs.getString("user_label"),
                isOwnAccount = rs.getBoolean("is_own_account") || ownAccountId != null,
                ownAccountId = ownAccountId,
            )
        }
    }
}
