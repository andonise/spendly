/**
 * Types matching contracts/openapi-draft.yaml (components/schemas).
 * Hand-written for now; see README "Swapping in openapi-generator".
 */

/** Decimal string with exactly 2 fraction digits, e.g. "-123.45". Negative = money out. */
export type Money = string;
/** ISO 4217 code, e.g. "RON". */
export type Currency = string;
/** ISO-8601 date, e.g. "2026-08-10". */
export type IsoDate = string;
/** ISO-8601 UTC timestamp, e.g. "2026-08-10T09:14:22Z". */
export type IsoDateTime = string;
export type Uuid = string;

export const ACCOUNT_TYPES = ['CURRENT', 'SAVINGS', 'CREDIT_CARD', 'E_WALLET'] as const;
export type AccountType = (typeof ACCOUNT_TYPES)[number];

export const CATEGORIES = [
  'GROCERIES', 'RESTAURANTS', 'FUEL', 'TRANSPORT', 'UTILITIES', 'RENT_MORTGAGE', 'SUBSCRIPTIONS',
  'SHOPPING', 'HEALTH', 'SPORT', 'ENTERTAINMENT', 'TRAVEL', 'EDUCATION', 'PETS', 'CASH_WITHDRAWAL',
  'BANK_FEES', 'CARD_INTEREST', 'FX_FEES', 'INSURANCE', 'TAXES', 'INCOME', 'INTERNAL_TRANSFER', 'OTHER',
] as const;
export type Category = (typeof CATEGORIES)[number];

export interface Account {
  id: Uuid;
  label: string;
  bank: string;
  type: AccountType;
  currency: Currency;
  /** Country code + "**…" + last 4, e.g. "RO**…3333". The full IBAN is never returned. */
  ibanMasked: string;
  cardLast4?: string | null;
  activeFrom: IsoDate;
  closedAt?: IsoDate | null;
  createdAt: IsoDateTime;
}

export interface AccountCreate {
  label: string;
  bank: string;
  type: AccountType;
  currency: Currency;
  /** Write-only full IBAN (spaces allowed). Send once; never keep it in state or logs. */
  iban: string;
  cardLast4?: string | null;
  activeFrom: IsoDate;
}

export interface AccountUpdate {
  label?: string;
  bank?: string;
  cardLast4?: string | null;
  closedAt?: IsoDate | null;
}

export const FILE_TYPES = ['CSV', 'XLS', 'XLSX', 'PDF'] as const;
export type FileType = (typeof FILE_TYPES)[number];

export const STATEMENT_STATUSES = [
  'UPLOADED', 'PARSED', 'CATEGORIZING', 'NEEDS_INPUT', 'CATEGORIZED', 'FAILED',
] as const;
export type StatementStatus = (typeof STATEMENT_STATUSES)[number];

export const TERMINAL_STATUSES: readonly StatementStatus[] = ['CATEGORIZED', 'FAILED'];
export const isTerminalStatus = (s: StatementStatus): boolean => TERMINAL_STATUSES.includes(s);

export type ProcessingStep = 'UPLOAD' | 'PARSE' | 'TRANSFER_DETECTION' | 'CATEGORIZE' | 'APPLY_ANSWERS';

export interface StatementError {
  code: string;
  message: string;
  retryable?: boolean;
}

export interface StatementProgress {
  transactionsImported?: number;
  transactionsCategorized?: number;
  questionsOpen?: number;
}

export interface CategoryTotal {
  category: Category;
  amountBase: Money;
  count: number;
}

export interface StatementSummary {
  transactionCount: number;
  spendBase: Money;
  incomeBase: Money;
  internalTransferCount: number;
  byCategory: CategoryTotal[];
}

export interface Statement {
  id: Uuid;
  accountId: Uuid;
  fileType: FileType;
  status: StatementStatus;
  lastCompletedStep?: ProcessingStep | null;
  periodStart?: IsoDate | null;
  periodEnd?: IsoDate | null;
  error?: StatementError | null;
  progress?: StatementProgress;
  summary?: StatementSummary | null;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

export interface UploadAccepted {
  statementId: Uuid;
  status: StatementStatus;
  duplicate: boolean;
}

export interface StatementStatusEvent {
  statementId: Uuid;
  status: StatementStatus;
  lastCompletedStep?: ProcessingStep | null;
  error?: StatementError | null;
  occurredAt: IsoDateTime;
}

export type QuestionKind = 'CATEGORY' | 'TRANSFER';
export type QuestionStatus = 'OPEN' | 'ANSWERED' | 'SKIPPED';

export interface QuestionTransaction {
  id: Uuid;
  accountId: Uuid;
  accountLabel?: string;
  bookingDate: IsoDate;
  amount: Money;
  currency: Currency;
  amountBase?: Money;
  /** Sanitized description. */
  description: string;
}

export interface Question {
  id: Uuid;
  kind: QuestionKind;
  statementId?: Uuid | null;
  transaction: QuestionTransaction;
  questionText: string;
  suggestions: Category[];
  status: QuestionStatus;
}

/** Exactly one of `category` or `skip = true`. */
export interface Answer {
  category?: Category | null;
  skip?: boolean;
  hint?: string | null;
  applyToFuture?: boolean;
}

export interface AnswerItem extends Answer {
  questionId: Uuid;
}

export interface AnswersSubmission {
  answers: AnswerItem[];
}

/** RFC 7807 problem details (`application/problem+json`). */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: { field?: string; message?: string }[];
}

// ------------------------------------------------------------------ Phase 3: transactions, counterparties

export const TRANSFER_STATUSES = ['NONE', 'CANDIDATE', 'INTERNAL', 'EXTERNAL'] as const;
/** INTERNAL = between own accounts (excluded from totals); CANDIDATE = unconfirmed (also excluded). */
export type TransferStatus = (typeof TRANSFER_STATUSES)[number];

export const CATEGORY_SOURCES = ['RULE', 'EMBEDDING', 'LLM', 'USER', 'TRANSFER_MATCH'] as const;
export type CategorySource = (typeof CATEGORY_SOURCES)[number];

export type CounterpartyKind = 'IBAN' | 'PERSON';

/** A counterparty as referenced from a transaction. `token` is an HMAC; never the raw IBAN/name. */
export interface CounterpartyRef {
  /** 64 hex chars. Display only a short prefix; never log it. */
  token: string;
  kind?: CounterpartyKind | null;
  label?: string | null;
  isOwnAccount: boolean;
  /** Set when the token equals a registered account's IBAN hash. */
  ownAccountId?: Uuid | null;
}

export interface Transaction {
  id: Uuid;
  accountId: Uuid;
  accountLabel?: string;
  statementId: Uuid;
  bookingDate: IsoDate;
  amount: Money;
  currency: Currency;
  amountBase?: Money | null;
  fxRate?: string | null;
  /** Sanitized description. */
  description: string;
  category?: Category | null;
  categorySource?: CategorySource | null;
  /** 0..1 */
  confidence?: number | null;
  transferStatus: TransferStatus;
  transferPairId?: Uuid | null;
  /** The other side of the transfer pair (for the "Transfer" badge link). */
  pairedTransactionId?: Uuid | null;
  counterparty?: CounterpartyRef | null;
}

export interface TransactionPage {
  items: Transaction[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export const TRANSACTION_SORTS = ['bookingDate,desc', 'bookingDate,asc', 'amount,desc', 'amount,asc'] as const;
export type TransactionSort = (typeof TRANSACTION_SORTS)[number];

/** Query parameters of `listTransactions`. */
export interface TransactionQuery {
  accountId?: Uuid;
  statementId?: Uuid;
  /** Booking date >= from. */
  from?: IsoDate;
  /** Booking date <= to (inclusive). */
  to?: IsoDate;
  category?: Category;
  transferStatus?: TransferStatus;
  /** 0-based, default 0. */
  page?: number;
  /** 1..200, default 50. */
  size?: number;
  /** Default `bookingDate,desc`. */
  sort?: TransactionSort;
}

export interface Counterparty extends CounterpartyRef {
  transactionCount: number;
  lastSeen?: IsoDate | null;
  /** One sanitized description, to help the user recognise it. */
  sampleDescription?: string | null;
}

export interface CounterpartyUpdate {
  label?: string | null;
  isOwnAccount: boolean;
}

// ---------------------------------------------------------------- Phase 4: reports and coverage

/** `PATCH /api/transactions/{id}/category` body. */
export interface CategoryUpdate {
  /** Any category except INTERNAL_TRANSFER. */
  category: Category;
  /** Learn the merchant (user alias) for future statements. */
  applyToFuture?: boolean;
}

export const GRANULARITIES = ['DAY', 'WEEK', 'MONTH', 'CUSTOM'] as const;
export type Granularity = (typeof GRANULARITIES)[number];

export type ReportStatus = 'PENDING' | 'READY' | 'STALE' | 'FAILED';
export type CoverageStatus = 'COMPLETE' | 'PARTIAL';

export interface DateRange {
  from: IsoDate;
  /** Inclusive. */
  to: IsoDate;
}

/** CANDIDATE transfers in the period (excluded from totals until confirmed or matched). */
export interface UnconfirmedTransfers {
  count: number;
  amountBase: Money;
}

export interface AccountCoverage {
  accountId: Uuid;
  accountLabel: string;
  activeFrom: IsoDate;
  closedAt?: IsoDate | null;
  status: CoverageStatus;
  /** Active days with no CATEGORIZED statement. */
  gaps: DateRange[];
}

export interface Coverage {
  from: IsoDate;
  to: IsoDate;
  status: CoverageStatus;
  /** Accounts active in the range. */
  accounts: AccountCoverage[];
  unconfirmedTransfers: UnconfirmedTransfers;
}

export interface ReportTotals {
  spendBase: Money;
  incomeBase: Money;
  /** income − spend (negative when spending exceeds income). */
  netBase: Money;
  savingsDepositsBase: Money;
  /** (income − spend) / income; null below month length or without income. */
  savingsRate?: number | null;
  transactionCount: number;
}

export interface ReportCategoryTotal {
  category: Category;
  amountBase: Money;
  count: number;
  /** Share of total spend, 0..1. */
  share: number;
}

export interface AccountTotal {
  accountId: Uuid;
  accountLabel: string;
  spendBase: Money;
  incomeBase: Money;
}

export interface MerchantTotal {
  merchantId?: Uuid | null;
  name: string;
  category?: Category | null;
  amountBase: Money;
  count: number;
}

export interface TrendPoint {
  from: IsoDate;
  to: IsoDate;
  spendBase: Money;
  incomeBase: Money;
  /** History points only. */
  coverage?: CoverageStatus | null;
}

export interface Baseline {
  periodsWanted: number;
  periodsRequired: number;
  periodsUsed: number;
  enoughHistory: boolean;
}

export type InsightType =
  | 'CATEGORY_SPIKE'
  | 'UNUSUAL_TRANSACTION'
  | 'MERCHANT_CONCENTRATION'
  | 'FEES_AND_INTEREST'
  | 'RECURRING_PAYMENTS'
  | 'DUPLICATE_SUBSCRIPTIONS'
  | 'SAVINGS_RATE';

export interface Insight {
  type: InsightType;
  severity: 'INFO' | 'WARNING';
  /** Short English sentence. */
  message: string;
  /** The numbers behind the message (amounts as Money strings); shape depends on `type`. */
  data: Record<string, unknown>;
}

export interface ReportSummary {
  coverage: Coverage;
  totals: ReportTotals;
  unconfirmedTransfers: UnconfirmedTransfers;
  /** Spend by category, largest first (never INCOME or INTERNAL_TRANSFER). */
  byCategory: ReportCategoryTotal[];
  byAccount: AccountTotal[];
  /** Top 10 by spend. */
  topMerchants: MerchantTotal[];
  /** One point per day of the period (empty for DAY). */
  trend: TrendPoint[];
  /** MONTH only: the 6 months ending with this one, oldest first. */
  history: TrendPoint[];
  baseline: Baseline;
}

export interface Report {
  id: Uuid;
  granularity: Granularity;
  periodStart: IsoDate;
  periodEnd: IsoDate;
  /** null = all accounts. */
  accountIds?: Uuid[] | null;
  status: ReportStatus;
  /** true while the numbers are from an older version and a rebuild is queued. */
  stale: boolean;
  version: number;
  builtAt?: IsoDateTime | null;
  currency: Currency;
  /** null until the first build succeeds. */
  summary?: ReportSummary | null;
  insights: Insight[];
}

/** Query parameters of `getReport`. `to` is required for CUSTOM only. */
export interface ReportQuery {
  granularity: Granularity;
  from: IsoDate;
  to?: IsoDate;
  /** Omitted or empty = all accounts (the combined view). */
  accountIds?: Uuid[];
}
