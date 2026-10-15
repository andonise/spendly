import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, firstValueFrom } from 'rxjs';
import { AccountsApi, QuestionsApi, StatementsApi } from '../api';
import { ACCOUNT_IDS } from './mock-data';
import { ManualClock, provideMockApiForTests, sampleFile } from './testing';

const STEP = 1000;

async function problemOf(call: Observable<unknown>): Promise<HttpErrorResponse> {
  try {
    await firstValueFrom(call);
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('expected the call to fail');
}

describe('Mock API', () => {
  let clock: ManualClock;
  let accounts: AccountsApi;
  let statements: StatementsApi;
  let questions: QuestionsApi;

  beforeEach(() => {
    clock = new ManualClock();
    TestBed.configureTestingModule({ providers: provideMockApiForTests({ stepMs: STEP, clock }) });
    accounts = TestBed.inject(AccountsApi);
    statements = TestBed.inject(StatementsApi);
    questions = TestBed.inject(QuestionsApi);
  });

  const status = async (id: string) => (await firstValueFrom(statements.getStatement(id))).status;

  it('runs the lifecycle: upload → PARSED → CATEGORIZING → NEEDS_INPUT → answers → CATEGORIZED; double submit → 409', async () => {
    const accepted = await firstValueFrom(statements.uploadStatement(ACCOUNT_IDS.current, sampleFile()));
    expect(accepted).toMatchObject({ status: 'UPLOADED', duplicate: false });
    const id = accepted.statementId;

    expect(await status(id)).toBe('UPLOADED');
    clock.advance(STEP);
    expect(await status(id)).toBe('PARSED');
    clock.advance(STEP);
    expect(await status(id)).toBe('CATEGORIZING');
    clock.advance(STEP);
    const needsInput = await firstValueFrom(statements.getStatement(id));
    expect(needsInput.status).toBe('NEEDS_INPUT');
    expect(needsInput.periodStart).toBe('2026-08-01');
    expect(needsInput.progress).toEqual({ transactionsImported: 18, transactionsCategorized: 15, questionsOpen: 3 });

    const qs = await firstValueFrom(questions.getStatementQuestions(id));
    expect(qs).toHaveLength(3);
    expect(qs.every((q) => q.status === 'OPEN' && q.statementId === id)).toBe(true);
    expect(qs[0].transaction.accountLabel).toBe('Generic Bank current');

    // Answers must cover every open question.
    const partial = await problemOf(
      questions.submitAnswers(id, { answers: [{ questionId: qs[0].id, category: 'RENT_MORTGAGE' }] }),
    );
    expect(partial.status).toBe(400);

    const afterSubmit = await firstValueFrom(
      questions.submitAnswers(id, {
        answers: [
          { questionId: qs[0].id, category: 'RENT_MORTGAGE', applyToFuture: true, hint: 'my landlord' },
          { questionId: qs[1].id, category: 'EDUCATION' },
          { questionId: qs[2].id, skip: true },
        ],
      }),
    );
    expect(afterSubmit.status).toBe('CATEGORIZING');

    const again = await problemOf(questions.submitAnswers(id, { answers: [{ questionId: qs[0].id, skip: true }] }));
    expect(again.status).toBe(409);
    expect(again.error).toMatchObject({ status: 409, code: 'STATEMENT_NOT_IN_NEEDS_INPUT' });

    clock.advance(STEP);
    const done = await firstValueFrom(statements.getStatement(id));
    expect(done.status).toBe('CATEGORIZED');
    expect(done.summary).toMatchObject({
      transactionCount: 18,
      spendBase: '5093.56',
      incomeBase: '8650.00',
      internalTransferCount: 2,
    });
    const other = done.summary!.byCategory.find((c) => c.category === 'OTHER');
    expect(other).toEqual({ category: 'OTHER', amountBase: '30.00', count: 1 }); // the skipped one
    expect(done.summary!.byCategory.find((c) => c.category === 'EDUCATION')?.amountBase).toBe('89.90');
    expect(done.summary!.byCategory.some((c) => c.category === 'INTERNAL_TRANSFER')).toBe(false);
    expect(done.summary!.byCategory.every((c) => !c.amountBase.startsWith('-'))).toBe(true);

    // Submitting after CATEGORIZED is still a conflict.
    expect((await problemOf(questions.submitAnswers(id, { answers: [{ questionId: qs[0].id, skip: true }] }))).status).toBe(409);
  });

  it('returns the existing statement with duplicate: true for the same file and account', async () => {
    const first = await firstValueFrom(statements.uploadStatement(ACCOUNT_IDS.current, sampleFile('aug.csv', 100)));
    const dup = await firstValueFrom(statements.uploadStatement(ACCOUNT_IDS.current, sampleFile('aug.csv', 100)));
    expect(dup).toEqual({ statementId: first.statementId, status: 'UPLOADED', duplicate: true });
    // Same file for another account is a new statement.
    const other = await firstValueFrom(statements.uploadStatement(ACCOUNT_IDS.revolut, sampleFile('aug.csv', 100)));
    expect(other.duplicate).toBe(false);
    expect(other.statementId).not.toBe(first.statementId);
  });

  it('fails files whose name contains "fail", and retry resumes the pipeline', async () => {
    const { statementId: id } = await firstValueFrom(statements.uploadStatement(ACCOUNT_IDS.current, sampleFile('will-fail.csv')));
    clock.advance(STEP);
    const failed = await firstValueFrom(statements.getStatement(id));
    expect(failed.status).toBe('FAILED');
    expect(failed.error).toMatchObject({ code: 'BALANCE_MISMATCH', retryable: true });

    const retried = await firstValueFrom(statements.retryStatement(id));
    expect(retried).toMatchObject({ status: 'UPLOADED', error: null });
    expect((await problemOf(statements.retryStatement(id))).status).toBe(409); // not FAILED any more
    clock.advance(3 * STEP);
    expect(await status(id)).toBe('NEEDS_INPUT');
  });

  it('validates uploads: unknown account 404, unsupported type 415', async () => {
    expect((await problemOf(statements.uploadStatement('nope', sampleFile()))).status).toBe(404);
    expect((await problemOf(statements.uploadStatement(ACCOUNT_IDS.current, sampleFile('notes.txt')))).status).toBe(415);
  });

  it('seeds accounts, a CATEGORIZED and a NEEDS_INPUT statement, and open questions', async () => {
    const list = await firstValueFrom(accounts.listAccounts());
    expect(list.map((a) => a.ibanMasked)).toEqual(['RO**…9999', 'RO**…3333', 'RO**…6666']);
    const all = await firstValueFrom(statements.listStatements());
    expect(all.map((s) => s.status).sort()).toEqual(['CATEGORIZED', 'NEEDS_INPUT']);
    const needsInput = await firstValueFrom(statements.listStatements({ status: 'NEEDS_INPUT' }));
    expect(needsInput).toHaveLength(1);
    const open = await firstValueFrom(questions.listOpenQuestions({ status: 'OPEN' }));
    expect(open).toHaveLength(3);
    expect(open.filter((q) => q.kind === 'TRANSFER' && q.statementId === null)).toHaveLength(1);
  });

  it('answers a single TRANSFER question, then 409 on a second answer', async () => {
    const [transfer] = (await firstValueFrom(questions.listOpenQuestions({ status: 'OPEN' }))).filter((q) => q.kind === 'TRANSFER');
    const answered = await firstValueFrom(questions.answerQuestion(transfer.id, { category: 'INTERNAL_TRANSFER' }));
    expect(answered.status).toBe('ANSWERED');
    expect((await problemOf(questions.answerQuestion(transfer.id, { skip: true }))).status).toBe(409);
    expect((await problemOf(questions.answerQuestion('missing', { skip: true }))).status).toBe(404);
  });

  it('accounts: creates with a masked IBAN only, validates, rejects duplicates, guards delete', async () => {
    const created = await firstValueFrom(
      accounts.createAccount({
        label: 'Savings', bank: 'Test Bank', type: 'SAVINGS', currency: 'RON',
        iban: 'RO38 TEST 7777 8888 9999 0000', activeFrom: '2026-01-01',
      }),
    );
    expect(created.ibanMasked).toBe('RO**…0000');
    expect(JSON.stringify(created)).not.toContain('7777');

    const invalid = await problemOf(
      accounts.createAccount({ label: '', bank: 'X', type: 'CURRENT', currency: 'RON', iban: 'RO00 0000', activeFrom: 'x' }),
    );
    expect(invalid.status).toBe(400);
    expect(invalid.error.errors.map((e: { field: string }) => e.field)).toEqual(['label', 'iban', 'activeFrom']);

    const duplicate = await problemOf(
      accounts.createAccount({
        label: 'Again', bank: 'Generic Bank', type: 'CURRENT', currency: 'RON',
        iban: 'RO54SPND0000111122223333', activeFrom: '2026-01-01',
      }),
    );
    expect(duplicate.status).toBe(409);

    const hasStatements = await problemOf(accounts.deleteAccount(ACCOUNT_IDS.card));
    expect(hasStatements.status).toBe(409);
    expect(hasStatements.error.code).toBe('ACCOUNT_HAS_STATEMENTS');

    const closed = await firstValueFrom(accounts.updateAccount(created.id, { closedAt: '2026-10-01' }));
    expect(closed.closedAt).toBe('2026-10-01');
    await firstValueFrom(accounts.deleteAccount(created.id));
    expect((await problemOf(accounts.getAccount(created.id))).status).toBe(404);
  });

  it('returns RFC 7807 404s for unknown statements', async () => {
    const error = await problemOf(statements.getStatement('missing'));
    expect(error.status).toBe(404);
    expect(error.headers.get('Content-Type')).toBe('application/problem+json');
    expect(error.error).toMatchObject({ title: 'Not Found', status: 404, code: 'STATEMENT_NOT_FOUND' });
  });
});
