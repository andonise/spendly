import { HttpParams } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { ibanProblem, maskIban, normalizeIban } from '../../shared/iban';
import { APP_ENV, ApiGroup } from '../config/app-env';
import {
  ACCOUNT_TYPES,
  Account,
  AccountCreate,
  AccountUpdate,
  Answer,
  AnswerItem,
  AnswersSubmission,
  CATEGORIES,
  Category,
  FileType,
  Problem,
  ProcessingStep,
  Question,
  STATEMENT_STATUSES,
  Statement,
  StatementError,
  StatementStatus,
  StatementSummary,
  UploadAccepted,
} from '../models/api.models';
import {
  ACCOUNT_IDS,
  CREDIT_CARD_AUGUST,
  GENERIC_BANK_AUGUST,
  REVOLUT_AUGUST,
  SEED_ACCOUNTS,
  SEED_IBANS,
  TxnTemplate,
} from './mock-data';

/** Time source for the simulated pipeline (ms since epoch). Tests replace it with a manual clock. */
export const MOCK_CLOCK = new InjectionToken<() => number>('MOCK_CLOCK', {
  providedIn: 'root',
  factory: () => () => Date.now(),
});

export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

export interface MockResponse {
  status: number;
  body: unknown;
  headers?: Record<string, string>;
}

/** Thrown by handlers; turned into an `application/problem+json` HttpErrorResponse. */
export class MockHttpError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
  ) {
    super(problem.title);
  }
}

/** Which endpoint group (= OpenAPI tag) a request belongs to; null = not an API call. */
export function apiGroupOf(path: string): ApiGroup | null {
  if (/^\/api\/accounts(\/|$)/.test(path)) return 'accounts';
  if (/^\/api\/statements\/[^/]+\/(questions|answers)$/.test(path)) return 'questions';
  if (/^\/api\/statements(\/|$)/.test(path)) return 'statements';
  if (/^\/api\/questions(\/|$)/.test(path)) return 'questions';
  return null;
}

interface MockTxn {
  id: string;
  accountId: string;
  date: string;
  description: string;
  amount: string;
  category: Category | null;
  template: TxnTemplate;
}

interface Transition {
  at: number;
  status: StatementStatus;
  step: ProcessingStep | null;
  error?: StatementError;
}

interface MockStatement {
  statement: Statement;
  fileName: string;
  fileSize: number;
  template: TxnTemplate[];
  txns: MockTxn[];
  plan: Transition[];
  /** The next parse fails (file name contains "fail"); a retry clears it. */
  failNextParse: boolean;
}

/**
 * In-memory implementation of every endpoint in contracts/openapi-draft.yaml.
 *
 * The statement lifecycle is simulated lazily: each statement holds a plan of timed
 * transitions that is applied whenever the statement is read, using MOCK_CLOCK. That makes
 * the simulation deterministic in tests (advance the clock) and timer-free in the app.
 */
@Injectable({ providedIn: 'root' })
export class MockBackend {
  private readonly now = inject(MOCK_CLOCK);
  private readonly stepMs = inject(APP_ENV).mockOptions.stepMs;

  private readonly accounts = new Map<string, Account>();
  /** Non-reversible IBAN fingerprints (stand-in for the backend's HMAC) → account id. */
  private readonly ibanFingerprints = new Map<string, string>();
  private readonly statements = new Map<string, MockStatement>();
  private readonly questions = new Map<string, Question>();
  private readonly questionTxn = new Map<string, MockTxn>();

  constructor() {
    this.seed();
  }

  handle(method: string, path: string, params: HttpParams, body: unknown): MockResponse {
    const m = method.toUpperCase();
    let match: RegExpMatchArray | null;

    // accounts
    if (path === '/api/accounts') {
      if (m === 'GET') return ok(this.listAccounts());
      if (m === 'POST') return this.createAccount(body as AccountCreate);
    }
    if ((match = path.match(/^\/api\/accounts\/([^/]+)$/))) {
      const id = decodeURIComponent(match[1]);
      if (m === 'GET') return ok(clone(this.requireAccount(id)));
      if (m === 'PATCH') return ok(this.updateAccount(id, body as AccountUpdate));
      if (m === 'DELETE') return this.deleteAccount(id);
    }

    // statements
    if (path === '/api/statements') {
      if (m === 'GET') return ok(this.listStatements(params));
      if (m === 'POST') return this.uploadStatement(body);
    }
    if ((match = path.match(/^\/api\/statements\/([^/]+)(?:\/(retry|events|questions|answers))?$/))) {
      const id = decodeURIComponent(match[1]);
      const sub = match[2];
      if (!sub && m === 'GET') return ok(clone(this.requireStatement(id).statement));
      if (sub === 'retry' && m === 'POST') return { status: 202, body: this.retryStatement(id) };
      if (sub === 'events' && m === 'GET') {
        throw problem(501, 'Not Implemented', 'The mock API has no SSE stream; poll GET /api/statements/{id}.');
      }
      if (sub === 'questions' && m === 'GET') return ok(this.getStatementQuestions(id));
      if (sub === 'answers' && m === 'POST') {
        return { status: 202, body: this.submitAnswers(id, body as AnswersSubmission) };
      }
    }

    // questions
    if (path === '/api/questions' && m === 'GET') return ok(this.listOpenQuestions(params));
    if ((match = path.match(/^\/api\/questions\/([^/]+)\/answer$/)) && m === 'POST') {
      return { status: 202, body: this.answerQuestion(decodeURIComponent(match[1]), body as Answer) };
    }

    throw problem(404, 'Not Found', `No mock for ${m} ${path}`);
  }

  // ---------------------------------------------------------------- accounts

  private listAccounts(): Account[] {
    return [...this.accounts.values()].sort((a, b) => a.label.localeCompare(b.label)).map(clone);
  }

  private createAccount(input: AccountCreate | null): MockResponse {
    const errors: FieldError[] = [];
    const body = (input ?? {}) as Partial<AccountCreate>;
    checkText(errors, 'label', body.label, 80, true);
    checkText(errors, 'bank', body.bank, 100, true);
    if (!ACCOUNT_TYPES.includes(body.type as never)) errors.push({ field: 'type', message: 'must be one of ' + ACCOUNT_TYPES.join(', ') });
    if (typeof body.currency !== 'string' || !/^[A-Z]{3}$/.test(body.currency)) errors.push({ field: 'currency', message: 'must be a 3-letter ISO code' });
    if (typeof body.iban !== 'string' || !body.iban.trim()) errors.push({ field: 'iban', message: 'is required' });
    else if (ibanProblem(body.iban)) errors.push({ field: 'iban', message: 'is not a valid IBAN' });
    checkCardLast4(errors, body.cardLast4);
    if (!isIsoDate(body.activeFrom)) errors.push({ field: 'activeFrom', message: 'must be a date (yyyy-MM-dd)' });
    if (errors.length) throw validation(errors);

    const fingerprint = fingerprintIban(body.iban!);
    if (this.ibanFingerprints.has(fingerprint)) {
      throw problem(409, 'Conflict', 'An account with this IBAN is already registered.', 'ACCOUNT_IBAN_EXISTS');
    }
    const account: Account = {
      id: uuid(),
      label: body.label!.trim(),
      bank: body.bank!.trim(),
      type: body.type!,
      currency: body.currency!,
      ibanMasked: maskIban(body.iban!),
      cardLast4: body.cardLast4 ?? null,
      activeFrom: body.activeFrom!,
      closedAt: null,
      createdAt: this.iso(),
    };
    this.accounts.set(account.id, account);
    this.ibanFingerprints.set(fingerprint, account.id);
    return { status: 201, body: clone(account), headers: { Location: `/api/accounts/${account.id}` } };
  }

  private updateAccount(id: string, input: AccountUpdate | null): Account {
    const account = this.requireAccount(id);
    const body = (input ?? {}) as Record<string, unknown>;
    const errors: FieldError[] = [];
    for (const field of ['iban', 'type', 'currency']) {
      if (field in body) errors.push({ field, message: 'is immutable' });
    }
    if ('label' in body) checkText(errors, 'label', body['label'], 80, true);
    if ('bank' in body) checkText(errors, 'bank', body['bank'], 100, true);
    if ('cardLast4' in body) checkCardLast4(errors, body['cardLast4']);
    const closedAt = body['closedAt'];
    if (closedAt !== undefined && closedAt !== null) {
      if (!isIsoDate(closedAt)) errors.push({ field: 'closedAt', message: 'must be a date (yyyy-MM-dd)' });
      else if (closedAt < account.activeFrom) errors.push({ field: 'closedAt', message: 'must not be before activeFrom' });
    }
    if (errors.length) throw validation(errors);

    if (typeof body['label'] === 'string') account.label = body['label'].trim();
    if (typeof body['bank'] === 'string') account.bank = body['bank'].trim();
    if ('cardLast4' in body) account.cardLast4 = (body['cardLast4'] as string | null) ?? null;
    if ('closedAt' in body) account.closedAt = (closedAt as string | null) ?? null;
    return clone(account);
  }

  private deleteAccount(id: string): MockResponse {
    this.requireAccount(id);
    const count = [...this.statements.values()].filter((s) => s.statement.accountId === id).length;
    if (count > 0) {
      throw problem(
        409,
        'Conflict',
        `The account still has ${count} statement${count === 1 ? '' : 's'}. Close it instead, or delete its statements first.`,
        'ACCOUNT_HAS_STATEMENTS',
      );
    }
    this.accounts.delete(id);
    for (const [fp, accountId] of this.ibanFingerprints) if (accountId === id) this.ibanFingerprints.delete(fp);
    return { status: 204, body: null };
  }

  private requireAccount(id: string): Account {
    const account = this.accounts.get(id);
    if (!account) throw problem(404, 'Not Found', `Account ${id} does not exist.`, 'ACCOUNT_NOT_FOUND');
    return account;
  }

  // ---------------------------------------------------------------- statements

  private listStatements(params: HttpParams): Statement[] {
    const accountId = params.get('accountId');
    const status = params.get('status');
    if (status && !STATEMENT_STATUSES.includes(status as StatementStatus)) {
      throw validation([{ field: 'status', message: 'must be one of ' + STATEMENT_STATUSES.join(', ') }]);
    }
    return [...this.statements.values()]
      .map((rec) => this.advance(rec).statement)
      .filter((s) => (!accountId || s.accountId === accountId) && (!status || s.status === status))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map(clone);
  }

  private uploadStatement(body: unknown): MockResponse {
    if (!(body instanceof FormData)) throw validation([{ field: 'file', message: 'multipart/form-data expected' }]);
    const accountId = body.get('accountId');
    const file = body.get('file');
    const errors: FieldError[] = [];
    if (typeof accountId !== 'string' || !accountId) errors.push({ field: 'accountId', message: 'is required' });
    if (!(file instanceof Blob)) errors.push({ field: 'file', message: 'is required' });
    if (errors.length) throw validation(errors);

    const account = this.requireAccount(accountId as string);
    const blob = file as File;
    const fileName = blob.name ?? 'upload';
    const fileType = fileTypeOf(fileName);
    if (!fileType) {
      throw problem(415, 'Unsupported Media Type', 'Upload a CSV, XLS, XLSX or PDF file.', 'UNSUPPORTED_FILE_TYPE');
    }
    if (blob.size > MAX_UPLOAD_BYTES) {
      throw problem(413, 'Payload Too Large', 'The file is larger than 20 MB.', 'FILE_TOO_LARGE');
    }
    if (blob.size === 0) throw validation([{ field: 'file', message: 'is empty' }]);

    // Stand-in for the backend's SHA-256 check: same name + size for the same account.
    const existing = [...this.statements.values()].find(
      (s) => s.statement.accountId === account.id && s.fileName === fileName && s.fileSize === blob.size,
    );
    if (existing) {
      const st = this.advance(existing).statement;
      return { status: 200, body: { statementId: st.id, status: st.status, duplicate: true } satisfies UploadAccepted };
    }

    const now = this.now();
    const rec: MockStatement = {
      statement: {
        id: uuid(),
        accountId: account.id,
        fileType,
        status: 'UPLOADED',
        lastCompletedStep: 'UPLOAD',
        periodStart: null,
        periodEnd: null,
        error: null,
        progress: { transactionsImported: 0, transactionsCategorized: 0, questionsOpen: 0 },
        summary: null,
        createdAt: this.iso(now),
        updatedAt: this.iso(now),
      },
      fileName,
      fileSize: blob.size,
      template: GENERIC_BANK_AUGUST,
      txns: [],
      plan: [],
      failNextParse: /fail/i.test(fileName),
    };
    this.planFrom(rec, now);
    this.statements.set(rec.statement.id, rec);
    const accepted: UploadAccepted = { statementId: rec.statement.id, status: 'UPLOADED', duplicate: false };
    return { status: 202, body: accepted, headers: { Location: `/api/statements/${rec.statement.id}` } };
  }

  private retryStatement(id: string): Statement {
    const rec = this.advance(this.requireStatement(id));
    if (rec.statement.status !== 'FAILED') {
      throw problem(409, 'Conflict', `Only FAILED statements can be retried (status is ${rec.statement.status}).`, 'STATEMENT_NOT_FAILED');
    }
    const now = this.now();
    const resumeStatus: StatementStatus =
      rec.statement.lastCompletedStep === 'UPLOAD' || !rec.statement.lastCompletedStep ? 'UPLOADED'
        : rec.statement.lastCompletedStep === 'CATEGORIZE' ? 'CATEGORIZING' : 'PARSED';
    rec.failNextParse = false;
    Object.assign(rec.statement, { status: resumeStatus, error: null, updatedAt: this.iso(now) });
    this.planFrom(rec, now);
    return clone(rec.statement);
  }

  private requireStatement(id: string): MockStatement {
    const rec = this.statements.get(id);
    if (!rec) throw problem(404, 'Not Found', `Statement ${id} does not exist.`, 'STATEMENT_NOT_FOUND');
    return this.advance(rec);
  }

  /** Schedules the remaining pipeline from the statement's current status. */
  private planFrom(rec: MockStatement, from: number): void {
    const step = this.stepMs;
    const plan: Transition[] = [];
    let t = from;
    const status = rec.statement.status;
    if (status === 'UPLOADED') {
      if (rec.failNextParse) {
        rec.plan = [{
          at: from + step,
          status: 'FAILED',
          step: 'UPLOAD',
          error: {
            code: 'BALANCE_MISMATCH',
            message: 'Opening balance + credits − debits does not match the closing balance (difference 12.50 RON).',
            retryable: true,
          },
        }];
        return;
      }
      plan.push({ at: (t += step), status: 'PARSED', step: 'PARSE' });
    }
    if (status === 'UPLOADED' || status === 'PARSED') {
      plan.push({ at: (t += step), status: 'CATEGORIZING', step: 'TRANSFER_DETECTION' });
    }
    const hasUnknowns = rec.template.some((x) => x.category === null);
    plan.push({ at: (t += step), status: hasUnknowns ? 'NEEDS_INPUT' : 'CATEGORIZED', step: 'CATEGORIZE' });
    rec.plan = plan;
  }

  /** Applies every transition whose time has come. */
  private advance(rec: MockStatement): MockStatement {
    const now = this.now();
    while (rec.plan.length && rec.plan[0].at <= now) this.apply(rec, rec.plan.shift()!);
    return rec;
  }

  private apply(rec: MockStatement, t: Transition): void {
    const st = rec.statement;
    st.status = t.status;
    st.lastCompletedStep = t.step;
    st.updatedAt = this.iso(t.at);
    st.error = t.error ?? null;
    const progress = (st.progress ??= {});
    switch (t.status) {
      case 'PARSED':
        this.importTxns(rec);
        break;
      case 'CATEGORIZING':
        if (!rec.txns.length) this.importTxns(rec);
        progress.transactionsCategorized = rec.txns.filter((x) => x.category !== null).length;
        break;
      case 'NEEDS_INPUT': {
        const open = this.createQuestions(rec);
        progress.questionsOpen = open;
        progress.transactionsCategorized = rec.txns.filter((x) => x.category !== null).length;
        break;
      }
      case 'CATEGORIZED':
        for (const txn of rec.txns) txn.category ??= 'OTHER';
        progress.questionsOpen = 0;
        progress.transactionsCategorized = rec.txns.length;
        st.summary = summarize(rec.txns);
        break;
    }
  }

  private importTxns(rec: MockStatement): void {
    rec.txns = rec.template.map((tpl) => ({
      id: uuid(),
      accountId: rec.statement.accountId,
      date: tpl.date,
      description: tpl.description,
      amount: tpl.amount,
      category: tpl.category,
      template: tpl,
    }));
    const dates = rec.txns.map((x) => x.date).sort();
    rec.statement.periodStart = dates[0] ?? null;
    rec.statement.periodEnd = dates[dates.length - 1] ?? null;
    rec.statement.progress = { transactionsImported: rec.txns.length, transactionsCategorized: 0, questionsOpen: 0 };
  }

  private createQuestions(rec: MockStatement): number {
    const account = this.accounts.get(rec.statement.accountId);
    let open = 0;
    for (const txn of rec.txns) {
      const q = txn.template.question;
      if (txn.category !== null || !q) continue;
      if ([...this.questionTxn.values()].includes(txn)) continue;
      const question: Question = {
        id: uuid(),
        kind: q.kind,
        statementId: q.global ? null : rec.statement.id,
        transaction: {
          id: txn.id,
          accountId: txn.accountId,
          accountLabel: account?.label,
          bookingDate: txn.date,
          amount: txn.amount,
          currency: 'RON',
          amountBase: txn.amount,
          description: txn.description,
        },
        questionText: q.text,
        suggestions: [...q.suggestions],
        status: 'OPEN',
      };
      this.questions.set(question.id, question);
      this.questionTxn.set(question.id, txn);
      if (!q.global) open++;
    }
    return open;
  }

  // ---------------------------------------------------------------- questions

  private getStatementQuestions(statementId: string): Question[] {
    this.requireStatement(statementId);
    return [...this.questions.values()]
      .filter((q) => q.statementId === statementId)
      .sort((a, b) => Number(b.status === 'OPEN') - Number(a.status === 'OPEN'))
      .map(clone);
  }

  private submitAnswers(statementId: string, body: AnswersSubmission | null): Statement {
    const rec = this.requireStatement(statementId);
    if (rec.statement.status !== 'NEEDS_INPUT') {
      throw problem(
        409,
        'Conflict',
        `Answers are accepted only while the statement needs input (status is ${rec.statement.status}).`,
        'STATEMENT_NOT_IN_NEEDS_INPUT',
      );
    }
    const answers = body?.answers;
    if (!Array.isArray(answers) || answers.length === 0) {
      throw validation([{ field: 'answers', message: 'must contain at least 1 item' }]);
    }
    const open = [...this.questions.values()].filter((q) => q.statementId === statementId && q.status === 'OPEN');
    const errors: FieldError[] = [];
    answers.forEach((a, i) => {
      const q = this.questions.get(a?.questionId);
      if (!q || q.statementId !== statementId) errors.push({ field: `answers[${i}].questionId`, message: 'is not a question of this statement' });
      else if (q.status !== 'OPEN') errors.push({ field: `answers[${i}].questionId`, message: 'is already answered' });
      checkAnswer(errors, a, `answers[${i}]`);
    });
    const answered = new Set(answers.map((a) => a?.questionId));
    for (const q of open) if (!answered.has(q.id)) errors.push({ field: 'answers', message: `question ${q.id} is not answered or skipped` });
    if (errors.length) throw validation(errors);

    for (const a of answers) this.applyAnswer(a);
    const now = this.now();
    Object.assign(rec.statement, {
      status: 'CATEGORIZING' satisfies StatementStatus,
      lastCompletedStep: 'CATEGORIZE' satisfies ProcessingStep,
      updatedAt: this.iso(now),
      progress: { ...rec.statement.progress, questionsOpen: 0 },
    });
    rec.plan = [{ at: now + this.stepMs, status: 'CATEGORIZED', step: 'APPLY_ANSWERS' }];
    return clone(rec.statement);
  }

  private listOpenQuestions(params: HttpParams): Question[] {
    const status = params.get('status');
    if (status && !['OPEN', 'ANSWERED', 'SKIPPED'].includes(status)) {
      throw validation([{ field: 'status', message: 'must be one of OPEN, ANSWERED, SKIPPED' }]);
    }
    for (const rec of this.statements.values()) this.advance(rec);
    return [...this.questions.values()].filter((q) => !status || q.status === status).map(clone);
  }

  private answerQuestion(questionId: string, body: Answer | null): Question {
    const q = this.questions.get(questionId);
    if (!q) throw problem(404, 'Not Found', `Question ${questionId} does not exist.`, 'QUESTION_NOT_FOUND');
    if (q.status !== 'OPEN') throw problem(409, 'Conflict', 'This question was already answered.', 'QUESTION_NOT_OPEN');
    const errors: FieldError[] = [];
    checkAnswer(errors, body, 'answer');
    if (errors.length) throw validation(errors);
    this.applyAnswer({ ...body!, questionId });
    return clone(q);
  }

  private applyAnswer(a: AnswerItem): void {
    const q = this.questions.get(a.questionId)!;
    q.status = a.skip ? 'SKIPPED' : 'ANSWERED';
    const txn = this.questionTxn.get(q.id);
    if (txn) txn.category = a.skip ? 'OTHER' : (a.category ?? 'OTHER');
  }

  // ---------------------------------------------------------------- seed

  private seed(): void {
    for (const account of SEED_ACCOUNTS) {
      this.accounts.set(account.id, clone(account));
      this.ibanFingerprints.set(fingerprintIban(SEED_IBANS[account.id]), account.id);
    }
    const seedStatement = (
      id: string, accountId: string, fileName: string, fileType: FileType, template: TxnTemplate[], createdAt: string,
    ): MockStatement => {
      const rec: MockStatement = {
        statement: {
          id, accountId, fileType, status: 'UPLOADED', lastCompletedStep: 'UPLOAD', createdAt, updatedAt: createdAt,
          periodStart: null, periodEnd: null, error: null, summary: null,
        },
        fileName, fileSize: 4096, template, txns: [], plan: [], failNextParse: false,
      };
      this.statements.set(id, rec);
      // Run the whole plan instantly.
      this.planFrom(rec, 0);
      for (const t of rec.plan) this.apply(rec, { ...t, at: Date.parse(createdAt) + 60_000 });
      rec.plan = [];
      return rec;
    };
    seedStatement('5a7c2d10-8e3b-4f6a-b1c2-0000000000c1', ACCOUNT_IDS.card, 'credit-card-2026-08.pdf', 'PDF',
      CREDIT_CARD_AUGUST, '2026-09-02T18:30:00Z');
    seedStatement('5a7c2d10-8e3b-4f6a-b1c2-0000000000b2', ACCOUNT_IDS.revolut, 'revolut-2026-08.csv', 'CSV',
      REVOLUT_AUGUST, '2026-09-02T18:35:00Z');
  }

  private iso(ms: number = this.now()): string {
    return new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');
  }
}

// ------------------------------------------------------------------ helpers

type FieldError = { field: string; message: string };

function ok(body: unknown): MockResponse {
  return { status: 200, body };
}

function problem(status: number, title: string, detail: string, code?: string, errors?: FieldError[]): MockHttpError {
  return new MockHttpError(status, { type: 'about:blank', title, status, detail, code, errors });
}

function validation(errors: FieldError[]): MockHttpError {
  const detail = errors.map((e) => `${e.field} ${e.message}`).join('; ');
  return problem(400, 'Bad Request', detail, 'VALIDATION_FAILED', errors);
}

function checkText(errors: FieldError[], field: string, value: unknown, max: number, required: boolean): void {
  if (typeof value !== 'string' || !value.trim()) {
    if (required) errors.push({ field, message: 'is required' });
  } else if (value.length > max) {
    errors.push({ field, message: `must be at most ${max} characters` });
  }
}

function checkCardLast4(errors: FieldError[], value: unknown): void {
  if (value !== undefined && value !== null && (typeof value !== 'string' || !/^[0-9]{4}$/.test(value))) {
    errors.push({ field: 'cardLast4', message: 'must be exactly 4 digits' });
  }
}

function checkAnswer(errors: FieldError[], a: Answer | null | undefined, field: string): void {
  const hasCategory = a?.category !== undefined && a?.category !== null;
  if (!a || hasCategory === !!a.skip) {
    errors.push({ field, message: 'needs exactly one of category or skip=true' });
  } else if (hasCategory && !CATEGORIES.includes(a.category as Category)) {
    errors.push({ field: `${field}.category`, message: 'is not a known category' });
  }
  if (a?.hint && a.hint.length > 500) errors.push({ field: `${field}.hint`, message: 'must be at most 500 characters' });
}

function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value));
}

function fileTypeOf(name: string): FileType | null {
  const ext = name.split('.').pop()?.toUpperCase();
  return ext === 'CSV' || ext === 'XLS' || ext === 'XLSX' || ext === 'PDF' ? ext : null;
}

/** FNV-1a over the normalized IBAN: lets the mock detect duplicates without keeping the IBAN. */
function fingerprintIban(iban: string): string {
  let hash = 0x811c9dc5;
  for (const ch of normalizeIban(iban)) {
    hash ^= ch.charCodeAt(0);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16);
}

function toCents(money: string): number {
  return Math.round(Number(money) * 100);
}

function fromCents(cents: number): string {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

/**
 * Totals for a categorized statement, per the contract's StatementSummary: all amounts are
 * absolute (non-negative); internal transfers are counted but excluded from totals and byCategory.
 */
export function summarize(txns: { amount: string; category: Category | null }[]): StatementSummary {
  const by = new Map<Category, { cents: number; count: number }>();
  let spend = 0;
  let income = 0;
  let internal = 0;
  for (const t of txns) {
    const category = t.category ?? 'OTHER';
    const cents = toCents(t.amount);
    if (category === 'INTERNAL_TRANSFER') {
      internal++;
      continue;
    }
    if (cents < 0) spend -= cents;
    else income += cents;
    const entry = by.get(category) ?? { cents: 0, count: 0 };
    entry.cents += cents;
    entry.count++;
    by.set(category, entry);
  }
  return {
    transactionCount: txns.length,
    spendBase: fromCents(spend),
    incomeBase: fromCents(income),
    internalTransferCount: internal,
    byCategory: [...by.entries()]
      .sort((a, b) => Math.abs(b[1].cents) - Math.abs(a[1].cents))
      .map(([category, v]) => ({ category, amountBase: fromCents(Math.abs(v.cents)), count: v.count })),
  };
}

function clone<T>(value: T): T {
  return structuredClone(value);
}

let fallbackCounter = 0;
function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  fallbackCounter++;
  return `00000000-0000-4000-8000-${String(fallbackCounter).padStart(12, '0')}`;
}
