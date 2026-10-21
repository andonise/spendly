import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Account, Report, ReportsApi, TransactionsApi } from '../api';
import { ACCOUNT_IDS } from './mock-data';
import { mockCoverage, periodOf } from './mock-reports';
import { provideMockApiForTests } from './testing';

describe('mock reports API', () => {
  let reports: ReportsApi;
  let transactions: TransactionsApi;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [...provideMockApiForTests()] });
    reports = TestBed.inject(ReportsApi);
    transactions = TestBed.inject(TransactionsApi);
  });

  const error = async (p: Promise<unknown>) => {
    try {
      await p;
    } catch (e) {
      return e as { status: number; error: { code?: string; errors?: { field: string }[] } };
    }
    throw new Error('expected an error');
  };

  it('returns the same report for the same request and reconciles per-account totals', async () => {
    const a = await firstValueFrom(reports.getReport({ granularity: 'MONTH', from: '2026-08-01' }));
    const b = await firstValueFrom(reports.getReport({ granularity: 'MONTH', from: '2026-08-01', to: '2026-08-31' }));
    expect(b.id).toBe(a.id);
    expect(await firstValueFrom(reports.getReportById(a.id))).toMatchObject({ id: a.id, periodEnd: '2026-08-31' });
    const sum = a.summary!.byAccount.reduce((s, x) => s + Number(x.spendBase), 0);
    expect(sum.toFixed(2)).toBe(a.summary!.totals.spendBase);
    expect(a.summary!.byCategory.some((c) => c.category === 'INTERNAL_TRANSFER' || c.category === 'INCOME')).toBe(false);
    expect(a.summary!.trend).toHaveLength(31);
    expect(a.summary!.history).toHaveLength(6);
  });

  it('validates periods and accounts like the backend', async () => {
    expect((await error(firstValueFrom(reports.getReport({ granularity: 'WEEK', from: '2026-08-11' })))).status).toBe(400);
    expect((await error(firstValueFrom(reports.getReport({ granularity: 'CUSTOM', from: '2026-08-11' })))).status).toBe(400);
    expect((await error(firstValueFrom(reports.getReport({ granularity: 'MONTH', from: '2026-08-01', accountIds: ['nope'] })))).status).toBe(400);
    expect((await error(firstValueFrom(reports.getReportById('missing')))).status).toBe(404);
    expect((await error(firstValueFrom(reports.getCoverage('2026-08-10', '2026-08-01')))).status).toBe(400);
    expect(periodOf('YEAR', '2026-01-01', null)).toEqual([{ field: 'granularity', message: expect.any(String) }]);
    expect(periodOf('DAY', '2026-01-01', '2026-01-02')).toEqual([{ field: 'to', message: 'must be 2026-01-01 for DAY' }]);
    expect(periodOf('CUSTOM', '2025-01-01', '2026-06-01')).toEqual([{ field: 'to', message: 'at most 366 days' }]);
  });

  it('a category correction changes the next report', async () => {
    const before: Report = await firstValueFrom(reports.getReport({ granularity: 'MONTH', from: '2026-08-01', accountIds: [ACCOUNT_IDS.current] }));
    const page = await firstValueFrom(transactions.listTransactions({ accountId: ACCOUNT_IDS.current, transferStatus: 'NONE', size: 200 }));
    const grocery = page.items.find((t) => t.category === 'GROCERIES' && t.amount.startsWith('-'))!;
    const updated = await firstValueFrom(transactions.updateTransactionCategory(grocery.id, { category: 'PETS' }));
    expect(updated).toMatchObject({ category: 'PETS', categorySource: 'USER' });
    const after = await firstValueFrom(reports.getReport({ granularity: 'MONTH', from: '2026-08-01', accountIds: [ACCOUNT_IDS.current] }));
    expect(after.summary!.byCategory.find((c) => c.category === 'PETS')).toBeDefined();
    expect(after.summary!.totals.spendBase).toBe(before.summary!.totals.spendBase);
  });

  it('applies the correction rules', async () => {
    const internal = (await firstValueFrom(transactions.listTransactions({ transferStatus: 'INTERNAL' }))).items[0];
    expect((await error(firstValueFrom(transactions.updateTransactionCategory(internal.id, { category: 'SHOPPING' })))).error.code).toBe('TRANSACTION_IS_TRANSFER');
    const any = (await firstValueFrom(transactions.listTransactions({ transferStatus: 'NONE' }))).items[0];
    expect((await error(firstValueFrom(transactions.updateTransactionCategory(any.id, { category: 'INTERNAL_TRANSFER' })))).status).toBe(400);
    expect((await error(firstValueFrom(transactions.updateTransactionCategory('missing', { category: 'PETS' })))).status).toBe(404);
  });

  it('coverage lists the gaps per account', () => {
    const account = { id: 'a', label: 'BT', activeFrom: '2026-01-01', closedAt: null } as Account;
    const c = mockCoverage({ from: '2026-08-01', to: '2026-08-31' }, [account], [{ accountId: 'a', from: '2026-08-15', to: '2026-08-31' }], []);
    expect(c.status).toBe('PARTIAL');
    expect(c.accounts[0].gaps).toEqual([{ from: '2026-08-01', to: '2026-08-14' }]);
    const closed = { ...account, closedAt: '2026-07-31' };
    expect(mockCoverage({ from: '2026-08-01', to: '2026-08-31' }, [closed], [], []).accounts).toEqual([]);
  });
});
