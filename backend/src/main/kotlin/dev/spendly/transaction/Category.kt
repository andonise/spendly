package dev.spendly.transaction

/** The fixed category enum (plan section 8, contracts/schemas/common.schema.json). */
enum class Category(val meaning: String) {
    GROCERIES("supermarkets, hypermarkets, food and grocery stores"),
    RESTAURANTS("restaurants, cafes, coffee shops, fast food chains, bars, food delivery"),
    FUEL("petrol and gas stations"),
    TRANSPORT("taxi and ride-hailing, public transport, metro, trains, buses, parking, tolls"),
    UTILITIES("electricity, gas, water, internet, cable TV, mobile phone bills and prepaid top-ups"),
    RENT_MORTGAGE("rent paid to a landlord, mortgage installments"),
    SUBSCRIPTIONS("recurring digital subscriptions: video/music streaming, software and cloud storage plans, premium memberships (not courses)"),
    SHOPPING("retail: electronics, clothing, home and DIY, furniture, online marketplaces, books, general stores"),
    HEALTH("pharmacies, clinics, hospitals, doctors, dentists, medical labs"),
    SPORT("gyms, fitness clubs, sports clubs and classes"),
    ENTERTAINMENT("cinema, concerts, theatre and event ticketing sites, video games and game stores, museums, leisure"),
    TRAVEL("flights, airlines, hotels, accommodation rentals, travel agencies"),
    EDUCATION("courses and online learning platforms (also when paid monthly), schools, universities, training, language lessons"),
    PETS("pet shops and pet supply stores, pet food, veterinarians"),
    CASH_WITHDRAWAL("cash withdrawals at ATMs"),
    BANK_FEES("bank fees and commissions, account maintenance, ATM fees"),
    CARD_INTEREST("interest charged on a credit card or overdraft"),
    FX_FEES("currency exchange and conversion fees"),
    INSURANCE("insurance premiums: car, home, life, health insurance"),
    TAXES("taxes and payments to the state: income tax, local taxes, ANAF, fines"),
    INCOME("money received: salary, refunds, payments from others"),
    INTERNAL_TRANSFER("money moved between the user's own accounts"),
    OTHER("anything that fits no other category");

    companion object {
        fun parse(value: String?): Category? = value?.trim()?.uppercase()?.let { v -> entries.firstOrNull { it.name == v } }

        /** Categories the LLM may choose (INTERNAL_TRANSFER is decided only by transfer detection or the user). */
        val forLlm: List<Category> = entries.filter { it != INTERNAL_TRANSFER }
    }
}

/** Which layer decided a category (transactions.category_source). */
enum class CategorySource { RULE, EMBEDDING, LLM, USER, TRANSFER_MATCH }

enum class TransferStatus { NONE, CANDIDATE, INTERNAL, EXTERNAL }

/** Why a transaction has its transfer status (transactions.transfer_reason). */
enum class TransferReason { OWN_IBAN, PAIR, PATTERN, OWN_COUNTERPARTY, LABELLED, USER }
