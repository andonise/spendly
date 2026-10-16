import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, firstValueFrom } from 'rxjs';
import { CounterpartiesApi, QuestionsApi, StatementsApi, Transaction, TransactionsApi } from '../api';
import { ACCOUNT_IDS, STATEMENT_IDS, syntheticToken } from './mock-data';
import { provideMockApiForTests } from './testing';

async function problemOf(call: Observable<unknown>): Promise<HttpErrorResponse> {
  try {
    await firstValueFrom(call);
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('expected the call to fail');
}

/** Seed: Generic Bank current 18 rows + Revolut 9 rows + credit card 5 rows, all in August 2026. */
const SEEDED = 32;

describe('Mock API: transactions and counterparties', () => {
  let transactions: TransactionsApi;
  let counterparties: CounterpartiesApi;
  let questions: QuestionsApi;
  let statements: StatementsApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideMockApiForTests() });
    transactions = TestBed.inject(TransactionsApi);
    counterparties = TestBed.inject(CounterpartiesApi);
    questions = TestBed.inject(QuestionsApi);
    statements = TestBed.inject(StatementsApi);
  });

  const all = async (query = {}) => (await firstValueFrom(transactions.listTransactions({ size: 200, ...query }))).items;
  const byDescription = async (text: string): Promise<Transaction> => (await all()).find((t) => t.description.includes(text))!;
  const openTransferQuestion = async () =>
    (await firstValueFrom(questions.listOpenQuestions({ status: 'OPEN' }))).find((q) => q.kind === 'TRANSFER')!;

  it('pages with defaults (size 50, bookingDate,desc) and reports totals', async () => {
    const first = await firstValueFrom(transactions.listTransactions());
    expect(first).toMatchObject({ page: 0, size: 50, totalItems: SEEDED, totalPages: 1 });
    expect(first.items).toHaveLength(SEEDED);
    const dates = first.items.map((t) => t.bookingDate);
    expect(dates).toEqual([...dates].sort().reverse());
    expect(first.items[0]).toMatchObject({ currency: 'RON', accountLabel: expect.any(String), statementId: expect.any(String) });

    const page3 = await firstValueFrom(transactions.listTransactions({ page: 3, size: 10 }));
    expect(page3).toMatchObject({ page: 3, size: 10, totalItems: SEEDED, totalPages: 4 });
    expect(page3.items).toHaveLength(2);
    // Pages don't overlap.
    const page0 = await firstValueFrom(transactions.listTransactions({ page: 0, size: 10 }));
    const ids = new Set(page0.items.map((t) => t.id));
    expect(page3.items.some((t) => ids.has(t.id))).toBe(false);
  });

  it('sorts by amount and bookingDate in both directions', async () => {
    expect((await all({ sort: 'amount,asc' }))[0].amount).toBe('-2200.00');
    expect((await all({ sort: 'amount,desc' }))[0].amount).toBe('8500.00');
    expect((await all({ sort: 'bookingDate,asc' }))[0].bookingDate).toBe('2026-08-01');
    expect((await all({ sort: 'bookingDate,desc' }))[0].bookingDate).toBe('2026-08-31');
  });

  it('filters by account, statement, date range, category and transfer status', async () => {
    expect(await all({ accountId: ACCOUNT_IDS.card })).toHaveLength(5);
    expect(await all({ statementId: STATEMENT_IDS.revolut })).toHaveLength(9);
    const range = await all({ from: '2026-08-10', to: '2026-08-11' });
    expect(range.map((t) => t.amount).sort()).toEqual(['-500.00', '500.00']);
    expect(await all({ category: 'GROCERIES' })).toHaveLength(4);
    expect((await all({ transferStatus: 'INTERNAL' })).map((t) => t.amount).sort()).toEqual(['-500.00', '-800.00', '500.00', '800.00']);
    const candidates = await all({ transferStatus: 'CANDIDATE' });
    expect(candidates).toHaveLength(1);
    expect(candidates[0]).toMatchObject({ description: 'Top-Up from RO**…7788', category: null, pairedTransactionId: null });
  });

  it('links the internal pairs both ways and labels the landlord transfer EXTERNAL', async () => {
    const bt = await byDescription('TRANSFER CATRE REVOLUT');
    const topUp = await byDescription('Top-Up by *3333');
    expect(bt).toMatchObject({ transferStatus: 'INTERNAL', pairedTransactionId: topUp.id, category: 'INTERNAL_TRANSFER', categorySource: 'TRANSFER_MATCH' });
    expect(topUp).toMatchObject({ transferStatus: 'INTERNAL', pairedTransactionId: bt.id, transferPairId: bt.transferPairId });
    expect(bt.counterparty).toMatchObject({ isOwnAccount: true, ownAccountId: ACCOUNT_IDS.revolut });

    const repayment = await byDescription('PLATA CARD CREDIT');
    const received = await byDescription('RAMBURSARE CARD');
    expect(repayment.pairedTransactionId).toBe(received.id);
    expect(received.pairedTransactionId).toBe(repayment.id);

    const rent = await byDescription('chirie august');
    expect(rent).toMatchObject({ transferStatus: 'EXTERNAL', category: 'RENT_MORTGAGE', categorySource: 'USER' });
    expect(rent.counterparty).toMatchObject({ label: 'Landlord', kind: 'IBAN', isOwnAccount: false });
    expect(rent.counterparty!.token).toMatch(/^[0-9a-f]{64}$/);

    const sources = new Set((await all()).map((t) => t.categorySource));
    expect([...sources]).toEqual(expect.arrayContaining(['RULE', 'EMBEDDING', 'LLM', 'USER', 'TRANSFER_MATCH']));
  });

  it('gets one transaction; 404 for unknown ids; 400 for invalid paging/filters', async () => {
    const rent = await byDescription('chirie august');
    expect(await firstValueFrom(transactions.getTransaction(rent.id))).toEqual(rent);
    const missing = await problemOf(transactions.getTransaction('missing'));
    expect(missing.status).toBe(404);
    expect(missing.error.code).toBe('TRANSACTION_NOT_FOUND');
    const bad = await problemOf(transactions.listTransactions({ size: 500, page: -1 }));
    expect(bad.status).toBe(400);
    expect(bad.error.errors.map((e: { field: string }) => e.field)).toEqual(['page', 'size']);
    expect((await problemOf(transactions.listTransactions({ sort: 'description,asc' as never }))).status).toBe(400);
  });

  it('keeps the seeded statement summaries absolute, with internal transfers counted but excluded', async () => {
    const current = (await firstValueFrom(statements.getStatement(STATEMENT_IDS.current))).summary!;
    expect(current).toMatchObject({ transactionCount: 18, internalTransferCount: 1 + 1, incomeBase: '8650.00', spendBase: '5093.56' });
    expect(current.byCategory.some((c) => c.category === 'INTERNAL_TRANSFER')).toBe(false);
    expect(current.byCategory.every((c) => !c.amountBase.startsWith('-'))).toBe(true);
    const card = (await firstValueFrom(statements.getStatement(STATEMENT_IDS.card))).summary!;
    expect(card).toMatchObject({ internalTransferCount: 1, spendBase: '951.00', incomeBase: '0.00' });
  });

  it('lists counterparties (most recent first) and labels one', async () => {
    const list = await firstValueFrom(counterparties.listCounterparties());
    expect(list.length).toBe(6);
    const lastSeen = list.map((c) => c.lastSeen!);
    expect(lastSeen).toEqual([...lastSeen].sort().reverse());
    const landlord = list.find((c) => c.label === 'Landlord')!;
    expect(landlord).toMatchObject({ kind: 'IBAN', isOwnAccount: false, transactionCount: 1, lastSeen: '2026-08-05' });
    expect(landlord.sampleDescription).toContain('chirie august');

    const friend = list.find((c) => c.token === syntheticToken('friend'))!;
    const updated = await firstValueFrom(counterparties.updateCounterparty(friend.token, { label: '  Ana  ', isOwnAccount: false }));
    expect(updated).toMatchObject({ label: 'Ana', isOwnAccount: false, transactionCount: 1 });
    expect((await byDescription('TRANSFER DE LA')).counterparty?.label).toBe('Ana');

    expect((await problemOf(counterparties.updateCounterparty('f'.repeat(64), { isOwnAccount: false }))).status).toBe(404);
    expect((await problemOf(counterparties.updateCounterparty(friend.token, {} as never))).status).toBe(400);
    expect((await problemOf(counterparties.updateCounterparty('NOT-A-TOKEN', { isOwnAccount: true }))).status).toBe(400);
    // A registered account's IBAN can't stop being an own account.
    const revolutIban = syntheticToken('revolutIban');
    const registered = await problemOf(counterparties.updateCounterparty(revolutIban, { isOwnAccount: false }));
    expect(registered.status).toBe(409);
    expect(registered.error.code).toBe('COUNTERPARTY_IS_REGISTERED_ACCOUNT');
  });

  it('isOwnAccount=true turns the counterparty’s transactions INTERNAL, and back when unset', async () => {
    const token = syntheticToken('unknownIban');
    const before = await byDescription('Top-Up from');
    expect(before.transferStatus).toBe('CANDIDATE');
    expect(await openTransferQuestion()).toBeDefined();

    const marked = await firstValueFrom(counterparties.updateCounterparty(token, { label: 'Savings at other bank', isOwnAccount: true }));
    expect(marked.isOwnAccount).toBe(true);
    const internal = await byDescription('Top-Up from');
    expect(internal).toMatchObject({ transferStatus: 'INTERNAL', category: 'INTERNAL_TRANSFER', categorySource: 'TRANSFER_MATCH' });
    expect(await openTransferQuestion()).toBeUndefined(); // resolved by the label

    await firstValueFrom(counterparties.updateCounterparty(token, { label: 'Savings at other bank', isOwnAccount: false }));
    const reverted = await byDescription('Top-Up from');
    expect(reverted).toMatchObject({ transferStatus: 'CANDIDATE', category: null, categorySource: null });
    expect(await openTransferQuestion()).toBeDefined(); // reopened

    // EXTERNAL rows are not touched (contract: only CANDIDATE/NONE become INTERNAL).
    await firstValueFrom(counterparties.updateCounterparty(syntheticToken('landlord'), { label: 'Landlord', isOwnAccount: true }));
    expect((await byDescription('chirie august')).transferStatus).toBe('EXTERNAL');
  });

  describe('TRANSFER answers (POST /api/questions/{id}/answer)', () => {
    it('yes (INTERNAL_TRANSFER) → INTERNAL', async () => {
      const q = await openTransferQuestion();
      await firstValueFrom(questions.answerQuestion(q.id, { category: 'INTERNAL_TRANSFER' }));
      const txn = await firstValueFrom(transactions.getTransaction(q.transaction.id));
      expect(txn).toMatchObject({ transferStatus: 'INTERNAL', category: 'INTERNAL_TRANSFER', categorySource: 'USER' });
      expect(txn.counterparty?.isOwnAccount).toBe(false); // no applyToFuture
    });

    it('no (another category) → EXTERNAL with that category', async () => {
      const q = await openTransferQuestion();
      await firstValueFrom(questions.answerQuestion(q.id, { category: 'INCOME' }));
      expect(await firstValueFrom(transactions.getTransaction(q.transaction.id))).toMatchObject({
        transferStatus: 'EXTERNAL', category: 'INCOME', categorySource: 'USER',
      });
    });

    it('skip → EXTERNAL + OTHER', async () => {
      const q = await openTransferQuestion();
      await firstValueFrom(questions.answerQuestion(q.id, { skip: true }));
      expect(await firstValueFrom(transactions.getTransaction(q.transaction.id))).toMatchObject({
        transferStatus: 'EXTERNAL', category: 'OTHER',
      });
    });

    it('applyToFuture → the counterparty becomes an own account', async () => {
      const q = await openTransferQuestion();
      await firstValueFrom(questions.answerQuestion(q.id, { category: 'INTERNAL_TRANSFER', applyToFuture: true }));
      const txn = await firstValueFrom(transactions.getTransaction(q.transaction.id));
      expect(txn.transferStatus).toBe('INTERNAL');
      expect(txn.counterparty?.isOwnAccount).toBe(true);
      const cp = (await firstValueFrom(counterparties.listCounterparties())).find((c) => c.token === syntheticToken('unknownIban'));
      expect(cp?.isOwnAccount).toBe(true);
    });

    it('applyToFuture is ignored unless the answer is INTERNAL_TRANSFER', async () => {
      const q = await openTransferQuestion();
      await firstValueFrom(questions.answerQuestion(q.id, { category: 'INCOME', applyToFuture: true }));
      const txn = await firstValueFrom(transactions.getTransaction(q.transaction.id));
      expect(txn.transferStatus).toBe('EXTERNAL');
      expect(txn.counterparty?.isOwnAccount).toBe(false);
    });
  });
});
