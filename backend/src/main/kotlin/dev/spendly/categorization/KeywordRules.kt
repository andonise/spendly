package dev.spendly.categorization

import java.math.BigDecimal

/**
 * System rules for generic banking vocabulary (fees, interest, cash, salary, rent) that are not merchants,
 * and for user labels on counterparties ("Landlord" → RENT_MORTGAGE, AC-MA-7). Part of layer 1 (RULE).
 * Romanian and English bank terms only; no merchant names.
 */
object KeywordRules {

    private enum class Sign { ANY, OUT, IN }

    private data class Rule(val patterns: List<String>, val category: Category, val sign: Sign = Sign.ANY, val prefix: Boolean = false)

    private val RULES = listOf(
        Rule(listOf("COMISION CONVERSIE", "CONVERSIE VALUTARA", "COMISION SCHIMB VALUTAR", "EXCHANGE FEE", "FX FEE",
            "CURRENCY CONVERSION FEE", "FOREIGN TRANSACTION FEE", "COMISION TRANZACTIE VALUTA"), Category.FX_FEES, Sign.OUT),
        Rule(listOf("FEE"), Category.BANK_FEES, Sign.OUT, prefix = true),
        Rule(listOf("COMISION", "TAXA ADMINISTRARE", "TAXA RETRAGERE", "ACCOUNT FEE", "MONTHLY FEE", "ATM FEE", "SERVICE FEE"),
            Category.BANK_FEES, Sign.OUT),
        Rule(listOf("DOBANDA CREDITOARE", "INTEREST EARNED", "INTEREST PAID TO YOU"), Category.INCOME, Sign.IN),
        Rule(listOf("DOBANDA", "DOBANZI", "INTEREST CHARGE", "INTEREST"), Category.CARD_INTEREST, Sign.OUT),
        Rule(listOf("RETRAGERE NUMERAR", "CASH WITHDRAWAL", "ATM WITHDRAWAL", "CASH AT"), Category.CASH_WITHDRAWAL, Sign.OUT),
        Rule(listOf("ATM"), Category.CASH_WITHDRAWAL, Sign.OUT, prefix = true),
        Rule(listOf("SALARIU", "SALARIUL", "SALARY", "PAYROLL"), Category.INCOME, Sign.IN),
        Rule(listOf("CHIRIE", "CHIRIA"), Category.RENT_MORTGAGE, Sign.OUT),
    )

    private val LABEL_RULES = listOf(
        Rule(listOf("LANDLORD", "PROPRIETAR", "PROPRIETARA", "CHIRIE", "RENT", "MORTGAGE", "IPOTECA"), Category.RENT_MORTGAGE),
        Rule(listOf("GYM", "SALA", "FITNESS", "ANTRENOR", "TRAINER"), Category.SPORT),
        Rule(listOf("VET", "VETERINAR"), Category.PETS),
        Rule(listOf("DOCTOR", "MEDIC", "DENTIST", "STOMATOLOG"), Category.HEALTH),
        Rule(listOf("SCHOOL", "SCOALA", "TUTOR", "MEDITATII", "GRADINITA", "KINDERGARTEN"), Category.EDUCATION),
        Rule(listOf("INSURANCE", "ASIGURARE", "ASIGURARI"), Category.INSURANCE),
        Rule(listOf("EMPLOYER", "ANGAJATOR", "SALARY", "SALARIU"), Category.INCOME, Sign.IN),
    )

    /** Category for a description from the system keyword rules, or null. [amount] null = sign unknown. */
    fun match(description: String, amount: BigDecimal?): Category? = apply(RULES, MatchKeys.of(description), amount)

    /** Category implied by a counterparty label the user wrote, or null. */
    fun matchLabel(label: String?, amount: BigDecimal?): Category? =
        label?.takeIf { it.isNotBlank() }?.let { apply(LABEL_RULES, MatchKeys.of(it), amount) }

    private fun apply(rules: List<Rule>, key: String, amount: BigDecimal?): Category? {
        for (rule in rules) {
            if (!signOk(rule.sign, amount)) continue
            val hit = rule.patterns.any { p -> if (rule.prefix) key == p || key.startsWith("$p ") else MatchKeys.containsWords(key, p) }
            if (hit) return rule.category
        }
        return null
    }

    private fun signOk(sign: Sign, amount: BigDecimal?): Boolean = when {
        amount == null || sign == Sign.ANY -> true
        sign == Sign.OUT -> amount.signum() <= 0
        else -> amount.signum() >= 0
    }
}
