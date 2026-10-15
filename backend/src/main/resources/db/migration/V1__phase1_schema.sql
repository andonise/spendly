-- Spendly Phase 1 schema (plan section 8). Flyway (this service) is the single schema owner.
-- Columns needed only in later phases are nullable. No balance columns anywhere (AC-SD-6),
-- no file path on statements (originals are deleted after parsing).

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------------------------
-- accounts: the IBAN is never stored, only HMAC("iban:" + compactIban) plus country and last 4.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounts (
    id           uuid         PRIMARY KEY,
    label        varchar(80)  NOT NULL,
    bank         varchar(100) NOT NULL,
    type         varchar(20)  NOT NULL,
    currency     varchar(3)   NOT NULL,
    iban_hash    varchar(64)  NOT NULL,
    iban_country varchar(2)   NOT NULL,
    iban_last4   varchar(4)   NOT NULL,
    card_last4   varchar(4),
    active_from  date         NOT NULL,
    closed_at    date,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_iban_hash UNIQUE (iban_hash),
    CONSTRAINT ck_accounts_type CHECK (type IN ('CURRENT', 'SAVINGS', 'CREDIT_CARD', 'E_WALLET')),
    CONSTRAINT ck_accounts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_accounts_iban_hash CHECK (iban_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_accounts_card_last4 CHECK (card_last4 IS NULL OR card_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_accounts_closed CHECK (closed_at IS NULL OR closed_at >= active_from)
);

-- ---------------------------------------------------------------------------------------------
-- statements
-- ---------------------------------------------------------------------------------------------
CREATE TABLE statements (
    id                  uuid         PRIMARY KEY,
    account_id          uuid         NOT NULL REFERENCES accounts (id),
    file_type           varchar(4)   NOT NULL,
    file_hash           varchar(64)  NOT NULL,
    status              varchar(20)  NOT NULL,
    last_completed_step varchar(30),
    period_start        date,
    period_end          date,
    error               jsonb,
    correlation_id      varchar(100) NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_statements_account_file_hash UNIQUE (account_id, file_hash),
    CONSTRAINT ck_statements_file_type CHECK (file_type IN ('CSV', 'XLS', 'XLSX', 'PDF')),
    CONSTRAINT ck_statements_file_hash CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_statements_status CHECK (status IN
        ('UPLOADED', 'PARSED', 'CATEGORIZING', 'NEEDS_INPUT', 'CATEGORIZED', 'FAILED')),
    CONSTRAINT ck_statements_step CHECK (last_completed_step IS NULL OR last_completed_step IN
        ('UPLOAD', 'PARSE', 'TRANSFER_DETECTION', 'CATEGORIZE', 'APPLY_ANSWERS')),
    CONSTRAINT ck_statements_period CHECK (period_start IS NULL OR period_end IS NULL OR period_end >= period_start)
);
CREATE INDEX ix_statements_account_status ON statements (account_id, status);
CREATE INDEX ix_statements_created_at ON statements (created_at DESC);

-- ---------------------------------------------------------------------------------------------
-- transactions: description holds AES-256-GCM ciphertext ("v1:" + base64(iv || ciphertext || tag)).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE transactions (
    id                      uuid           PRIMARY KEY,
    statement_id            uuid           NOT NULL REFERENCES statements (id),
    account_id              uuid           NOT NULL REFERENCES accounts (id),
    sequence                integer        NOT NULL,
    booking_date            date           NOT NULL,
    value_date              date,
    amount                  numeric(17, 2) NOT NULL,
    currency                varchar(3)     NOT NULL,
    amount_base             numeric(17, 2),
    fx_rate                 numeric(18, 8),
    description             text           NOT NULL,
    counterparty_token      varchar(64),
    counterparty_token_kind varchar(10),
    merchant_id             uuid,
    category                varchar(30),
    category_source         varchar(20),
    confidence              numeric(4, 3),
    transfer_status         varchar(12)    NOT NULL DEFAULT 'NONE',
    transfer_pair_id        uuid,
    tx_hash                 varchar(64)    NOT NULL,
    created_at              timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_transactions_account_tx_hash UNIQUE (account_id, tx_hash),
    CONSTRAINT ck_transactions_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_transactions_tx_hash CHECK (tx_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transactions_counterparty CHECK (counterparty_token IS NULL OR counterparty_token ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transactions_counterparty_kind CHECK (counterparty_token_kind IS NULL OR counterparty_token_kind IN ('IBAN', 'PERSON')),
    CONSTRAINT ck_transactions_category CHECK (category IS NULL OR category IN
        ('GROCERIES', 'RESTAURANTS', 'FUEL', 'TRANSPORT', 'UTILITIES', 'RENT_MORTGAGE', 'SUBSCRIPTIONS',
         'SHOPPING', 'HEALTH', 'SPORT', 'ENTERTAINMENT', 'TRAVEL', 'EDUCATION', 'PETS', 'CASH_WITHDRAWAL',
         'BANK_FEES', 'CARD_INTEREST', 'FX_FEES', 'INSURANCE', 'TAXES', 'INCOME', 'INTERNAL_TRANSFER', 'OTHER')),
    CONSTRAINT ck_transactions_category_source CHECK (category_source IS NULL OR category_source IN
        ('RULE', 'EMBEDDING', 'LLM', 'USER', 'TRANSFER_MATCH')),
    CONSTRAINT ck_transactions_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT ck_transactions_transfer_status CHECK (transfer_status IN ('NONE', 'CANDIDATE', 'INTERNAL', 'EXTERNAL'))
);
CREATE INDEX ix_transactions_statement ON transactions (statement_id, sequence);
CREATE INDEX ix_transactions_account_date ON transactions (account_id, booking_date);

-- ---------------------------------------------------------------------------------------------
-- outbox: payload is the complete event envelope (the Kafka value); id = envelope eventId.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE outbox (
    id           uuid         PRIMARY KEY,
    seq          bigserial    NOT NULL,
    aggregate_id uuid         NOT NULL,
    event_type   varchar(60)  NOT NULL,
    topic        varchar(200) NOT NULL,
    message_key  varchar(100) NOT NULL,
    payload      jsonb        NOT NULL,
    headers      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    attempts     integer      NOT NULL DEFAULT 0,
    last_error   varchar(500)
);
CREATE INDEX ix_outbox_unpublished ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_aggregate ON outbox (aggregate_id);

-- ---------------------------------------------------------------------------------------------
-- processed_events: consumer idempotency, inserted in the same transaction as the effect.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE processed_events (
    event_id     uuid         NOT NULL,
    consumer     varchar(100) NOT NULL,
    processed_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- ---------------------------------------------------------------------------------------------
-- fx_rates: BNR reference rates. rate_to_base = units of RON (the BNR base, and the default
-- SPENDLY_BASE_CURRENCY) per 1 unit of `currency` (BNR value / multiplier).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE fx_rates (
    date         date           NOT NULL,
    currency     varchar(3)     NOT NULL,
    rate_to_base numeric(18, 8) NOT NULL,
    source       varchar(10)    NOT NULL,
    fetched_at   timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (currency, date),
    CONSTRAINT ck_fx_rates_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_fx_rates_rate CHECK (rate_to_base > 0),
    CONSTRAINT ck_fx_rates_source CHECK (source IN ('BNR', 'MANUAL'))
);
