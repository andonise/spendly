-- Spendly Phase 3 schema: internal transfers, categorization layers, human-in-the-loop (plan sections 8-10).
-- V1 already has transactions.merchant_id/category/category_source/confidence/transfer_status/transfer_pair_id.

-- ---------------------------------------------------------------------------------------------
-- merchants: normalized merchant names ("Kaufland"); embedding of the name for the similarity layer
-- (computed lazily by the backend, outside any DB transaction).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE merchants (
    id               uuid         PRIMARY KEY,
    normalized_name  varchar(120) NOT NULL,
    match_key        varchar(120) NOT NULL,
    default_category varchar(30),
    embedding        vector(768),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_merchants_match_key UNIQUE (match_key),
    CONSTRAINT ck_merchants_category CHECK (default_category IS NULL OR default_category IN
        ('GROCERIES', 'RESTAURANTS', 'FUEL', 'TRANSPORT', 'UTILITIES', 'RENT_MORTGAGE', 'SUBSCRIPTIONS',
         'SHOPPING', 'HEALTH', 'SPORT', 'ENTERTAINMENT', 'TRAVEL', 'EDUCATION', 'PETS', 'CASH_WITHDRAWAL',
         'BANK_FEES', 'CARD_INTEREST', 'FX_FEES', 'INSURANCE', 'TAXES', 'INCOME', 'INTERNAL_TRANSFER', 'OTHER'))
);
CREATE INDEX ix_merchants_embedding ON merchants USING hnsw (embedding vector_cosine_ops);

-- ---------------------------------------------------------------------------------------------
-- merchant_aliases: rules layer. `pattern` is a match key (uppercase, no diacritics, words separated by
-- single spaces) that must appear as a whole-word sequence in the transaction's match key.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE merchant_aliases (
    id          uuid         PRIMARY KEY,
    pattern     varchar(120) NOT NULL,
    merchant_id uuid         NOT NULL REFERENCES merchants (id) ON DELETE CASCADE,
    created_by  varchar(10)  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_merchant_aliases_pattern UNIQUE (pattern),
    CONSTRAINT ck_merchant_aliases_created_by CHECK (created_by IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_merchant_aliases_pattern CHECK (pattern ~ '^[A-Z0-9][A-Z0-9 .&''+*#/-]*$')
);
CREATE INDEX ix_merchant_aliases_merchant ON merchant_aliases (merchant_id);

-- ---------------------------------------------------------------------------------------------
-- merchant_hints: free-text knowledge from the user (merchant_id NULL = global hint). Encrypted like descriptions.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE merchant_hints (
    id          uuid        PRIMARY KEY,
    merchant_id uuid        REFERENCES merchants (id) ON DELETE CASCADE,
    text        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_merchant_hints_merchant ON merchant_hints (merchant_id);

-- ---------------------------------------------------------------------------------------------
-- counterparties: one row per HMAC token seen in transactions (IBAN or person), with the user's label.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE counterparties (
    token          varchar(64) PRIMARY KEY,
    kind           varchar(10),
    user_label     varchar(80),
    is_own_account boolean     NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_counterparties_token CHECK (token ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_counterparties_kind CHECK (kind IS NULL OR kind IN ('IBAN', 'PERSON'))
);

-- ---------------------------------------------------------------------------------------------
-- transfer_pairs: an outgoing transaction in one account matched with an incoming one in another.
-- Each transaction belongs to at most one pair.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE transfer_pairs (
    id             uuid        PRIMARY KEY,
    outgoing_tx_id uuid        NOT NULL REFERENCES transactions (id),
    incoming_tx_id uuid        NOT NULL REFERENCES transactions (id),
    match_method   varchar(12) NOT NULL,
    matched_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfer_pairs_outgoing UNIQUE (outgoing_tx_id),
    CONSTRAINT uq_transfer_pairs_incoming UNIQUE (incoming_tx_id),
    CONSTRAINT ck_transfer_pairs_distinct CHECK (outgoing_tx_id <> incoming_tx_id),
    CONSTRAINT ck_transfer_pairs_method CHECK (match_method IN ('IBAN', 'AMOUNT_DATE', 'USER'))
);

-- ---------------------------------------------------------------------------------------------
-- transactions: Phase 3 links and how the transfer status was decided.
-- transfer_reason: OWN_IBAN, PAIR, PATTERN, OWN_COUNTERPARTY, LABELLED (labelled third party), USER.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE transactions
    ADD COLUMN transfer_reason varchar(20),
    ADD CONSTRAINT fk_transactions_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    ADD CONSTRAINT fk_transactions_transfer_pair FOREIGN KEY (transfer_pair_id) REFERENCES transfer_pairs (id),
    ADD CONSTRAINT ck_transactions_transfer_reason CHECK (transfer_reason IS NULL OR transfer_reason IN
        ('OWN_IBAN', 'PAIR', 'PATTERN', 'OWN_COUNTERPARTY', 'LABELLED', 'USER'));
CREATE INDEX ix_transactions_counterparty ON transactions (counterparty_token) WHERE counterparty_token IS NOT NULL;
CREATE INDEX ix_transactions_transfer_open ON transactions (booking_date)
    WHERE transfer_pair_id IS NULL AND transfer_status IN ('NONE', 'CANDIDATE', 'INTERNAL');

-- ---------------------------------------------------------------------------------------------
-- questions (human-in-the-loop). statement_id is NULL for TRANSFER questions raised by coverage.
-- At most one OPEN question per transaction and kind.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE questions (
    id             uuid        PRIMARY KEY,
    kind           varchar(10) NOT NULL,
    statement_id   uuid        REFERENCES statements (id),
    transaction_id uuid        NOT NULL REFERENCES transactions (id),
    question_text  text        NOT NULL,
    suggestions    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status         varchar(10) NOT NULL DEFAULT 'OPEN',
    created_at     timestamptz NOT NULL DEFAULT now(),
    resolved_at    timestamptz,
    CONSTRAINT ck_questions_kind CHECK (kind IN ('CATEGORY', 'TRANSFER')),
    CONSTRAINT ck_questions_status CHECK (status IN ('OPEN', 'ANSWERED', 'SKIPPED')),
    CONSTRAINT ck_questions_statement CHECK (kind = 'TRANSFER' OR statement_id IS NOT NULL)
);
CREATE UNIQUE INDEX uq_questions_open_per_tx ON questions (transaction_id, kind) WHERE status = 'OPEN';
CREATE INDEX ix_questions_statement ON questions (statement_id, status);
CREATE INDEX ix_questions_status ON questions (status, created_at);

-- ---------------------------------------------------------------------------------------------
-- answers: one per question. applied_at is set by the resume handler (or immediately for TRANSFER answers).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE answers (
    id              uuid        PRIMARY KEY,
    question_id     uuid        NOT NULL REFERENCES questions (id),
    category        varchar(30),
    skipped         boolean     NOT NULL DEFAULT false,
    hint            text,
    apply_to_future boolean     NOT NULL DEFAULT false,
    answered_at     timestamptz NOT NULL DEFAULT now(),
    applied_at      timestamptz,
    CONSTRAINT uq_answers_question UNIQUE (question_id),
    CONSTRAINT ck_answers_category CHECK (category IS NULL OR category IN
        ('GROCERIES', 'RESTAURANTS', 'FUEL', 'TRANSPORT', 'UTILITIES', 'RENT_MORTGAGE', 'SUBSCRIPTIONS',
         'SHOPPING', 'HEALTH', 'SPORT', 'ENTERTAINMENT', 'TRAVEL', 'EDUCATION', 'PETS', 'CASH_WITHDRAWAL',
         'BANK_FEES', 'CARD_INTEREST', 'FX_FEES', 'INSURANCE', 'TAXES', 'INCOME', 'INTERNAL_TRANSFER', 'OTHER')),
    CONSTRAINT ck_answers_one_of CHECK (skipped OR category IS NOT NULL)
);

-- ---------------------------------------------------------------------------------------------
-- categorization_examples: few-shot examples from answers. raw_description is the sanitized description,
-- encrypted like transactions.description; the embedding is of its normalized merchant text.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE categorization_examples (
    id               uuid        PRIMARY KEY,
    raw_description  text        NOT NULL,
    category         varchar(30) NOT NULL,
    embedding        vector(768),
    source_answer_id uuid        REFERENCES answers (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_examples_category CHECK (category IN
        ('GROCERIES', 'RESTAURANTS', 'FUEL', 'TRANSPORT', 'UTILITIES', 'RENT_MORTGAGE', 'SUBSCRIPTIONS',
         'SHOPPING', 'HEALTH', 'SPORT', 'ENTERTAINMENT', 'TRAVEL', 'EDUCATION', 'PETS', 'CASH_WITHDRAWAL',
         'BANK_FEES', 'CARD_INTEREST', 'FX_FEES', 'INSURANCE', 'TAXES', 'INCOME', 'INTERNAL_TRANSFER', 'OTHER')),
    CONSTRAINT uq_examples_answer UNIQUE (source_answer_id)
);
CREATE INDEX ix_examples_embedding ON categorization_examples USING hnsw (embedding vector_cosine_ops);
