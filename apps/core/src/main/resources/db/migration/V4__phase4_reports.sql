-- Spendly Phase 4 schema: daily aggregates, reports, insights (plan sections 5, 7 and 8).

-- ---------------------------------------------------------------------------------------------
-- daily_aggregates: per account and day, built only from CATEGORIZED statements by the aggregator
-- (delete + insert for the changed dates, under a per-account advisory lock). Amounts are absolute,
-- in the base currency.
--   SPEND / INCOME: money out / in that is not a transfer between own accounts (per category and merchant)
--   SAVINGS_DEPOSIT: INTERNAL transfer into a SAVINGS account (tracked for the savings rate, not spend)
--   UNCONFIRMED_TRANSFER: CANDIDATE transfers (excluded from totals, shown as "unconfirmed")
-- Other INTERNAL transactions are not aggregated at all.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE daily_aggregates (
    id          bigserial      PRIMARY KEY,
    date        date           NOT NULL,
    account_id  uuid           NOT NULL REFERENCES accounts (id),
    kind        varchar(24)    NOT NULL,
    category    varchar(30),
    merchant_id uuid           REFERENCES merchants (id),
    amount_base numeric(17, 2) NOT NULL,
    tx_count    integer        NOT NULL,
    CONSTRAINT ck_daily_aggregates_kind CHECK (kind IN ('SPEND', 'INCOME', 'SAVINGS_DEPOSIT', 'UNCONFIRMED_TRANSFER')),
    CONSTRAINT ck_daily_aggregates_amount CHECK (amount_base >= 0),
    CONSTRAINT ck_daily_aggregates_count CHECK (tx_count > 0)
);
CREATE UNIQUE INDEX uq_daily_aggregates ON daily_aggregates
    (account_id, date, kind, COALESCE(category, ''), COALESCE(merchant_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX ix_daily_aggregates_date ON daily_aggregates (date, account_id);
CREATE INDEX ix_daily_aggregates_merchant ON daily_aggregates (merchant_id, date) WHERE merchant_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- reports: one per (granularity, period, account scope). account_scope = 'ALL' or the sorted,
-- comma-separated account IDs.
--   version           incremented whenever the report's inputs change (READY → STALE); a build saves
--                     only while version is unchanged (optimistic locking, AC-TX-3)
--   requested_version the highest version a ReportRequested was written for (avoids duplicate requests)
--   built_version     the version `summary` and the insights were built from (NULL = never built)
--   summary           every number the report shows, from one REPEATABLE READ snapshot (AC-TX-5)
--   accounts_fingerprint  hash of the scope's accounts (id, active_from, closed_at) at build time; a
--                     difference on read means the account set changed, so the report is stale
--   recommendations, model, prompt_version: Phase 5
-- ---------------------------------------------------------------------------------------------
CREATE TABLE reports (
    id                   uuid          PRIMARY KEY,
    granularity          varchar(10)   NOT NULL,
    period_start         date          NOT NULL,
    period_end           date          NOT NULL,
    account_scope        varchar(4000) NOT NULL,
    status               varchar(10)   NOT NULL,
    version              integer       NOT NULL DEFAULT 1,
    requested_version    integer       NOT NULL DEFAULT 0,
    built_version        integer,
    coverage             varchar(10),
    coverage_gaps        jsonb,
    summary              jsonb,
    accounts_fingerprint varchar(64),
    recommendations      jsonb,
    model                varchar(100),
    prompt_version       varchar(40),
    error                varchar(500),
    built_at             timestamptz,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_reports_identity UNIQUE (granularity, period_start, period_end, account_scope),
    CONSTRAINT ck_reports_granularity CHECK (granularity IN ('DAY', 'WEEK', 'MONTH', 'CUSTOM')),
    CONSTRAINT ck_reports_status CHECK (status IN ('PENDING', 'READY', 'STALE', 'FAILED')),
    CONSTRAINT ck_reports_coverage CHECK (coverage IS NULL OR coverage IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT ck_reports_period CHECK (period_end >= period_start),
    CONSTRAINT ck_reports_version CHECK (version >= 1 AND requested_version <= version AND (built_version IS NULL OR built_version <= version)),
    CONSTRAINT ck_reports_scope CHECK (account_scope = 'ALL' OR account_scope ~ '^[0-9a-f-]{36}(,[0-9a-f-]{36})*$')
);
CREATE INDEX ix_reports_period ON reports (period_start, period_end);

-- ---------------------------------------------------------------------------------------------
-- insights: the findings of the insight rules for one report version, in display order.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE insights (
    id             uuid        PRIMARY KEY,
    report_id      uuid        NOT NULL REFERENCES reports (id) ON DELETE CASCADE,
    report_version integer     NOT NULL,
    position       integer     NOT NULL,
    type           varchar(30) NOT NULL,
    severity       varchar(10) NOT NULL,
    message        text        NOT NULL,
    payload        jsonb       NOT NULL,
    CONSTRAINT uq_insights_position UNIQUE (report_id, report_version, position),
    CONSTRAINT ck_insights_type CHECK (type IN ('CATEGORY_SPIKE', 'UNUSUAL_TRANSACTION', 'MERCHANT_CONCENTRATION',
        'FEES_AND_INTEREST', 'RECURRING_PAYMENTS', 'DUPLICATE_SUBSCRIPTIONS', 'SAVINGS_RATE')),
    CONSTRAINT ck_insights_severity CHECK (severity IN ('INFO', 'WARNING'))
);
