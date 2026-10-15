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
