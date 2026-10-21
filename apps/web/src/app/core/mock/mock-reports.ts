import {
  Account,
  Category,
  Coverage,
  DateRange,
  GRANULARITIES,
  Granularity,
  Insight,
  MerchantTotal,
  Report,
  ReportCategoryTotal,
  TransferStatus,
  TrendPoint,
} from '../models/api.models';
import { addDays, addMonths, daysBetween, endOfMonth, weekday } from '../../shared/dates';

/**
 * Report and coverage math for the mock API: a simplified version of the backend's (no baselines, so no comparison
 * insights). Same rules for what counts: only CATEGORIZED statements, INTERNAL transfers left out, CANDIDATE
 * transfers shown as unconfirmed.
 */

export interface ReportTxn {
  accountId: string;
  date: string;
  amount: string;
  category: Category | null;
  transferStatus: TransferStatus;
  description: string;
}

/** Days covered by one CATEGORIZED statement. */
export interface CoveredSpan {
  accountId: string;
  from: string;
  to: string;
}

export interface FieldProblem {
  field: string;
  message: string;
}

/** Same rules as the backend's Period.of. Returns the range or the field problems. */
export function periodOf(granularity: string | null, from: string | null, to: string | null): DateRange | FieldProblem[] {
  const isDate = (v: string | null): v is string => !!v && /^\d{4}-\d{2}-\d{2}$/.test(v);
  if (!GRANULARITIES.includes(granularity as Granularity)) return [{ field: 'granularity', message: 'must be one of ' + GRANULARITIES.join(', ') }];
  if (!isDate(from)) return [{ field: 'from', message: 'must be a date (yyyy-MM-dd)' }];
  if (to !== null && !isDate(to)) return [{ field: 'to', message: 'must be a date (yyyy-MM-dd)' }];
  let expected: string;
  switch (granularity as Granularity) {
    case 'DAY':
      expected = from;
      break;
    case 'WEEK':
      if (weekday(from) !== 1) return [{ field: 'from', message: 'a WEEK starts on a Monday' }];
      expected = addDays(from, 6);
      break;
    case 'MONTH':
      if (!from.endsWith('-01')) return [{ field: 'from', message: 'a MONTH starts on the 1st' }];
      expected = endOfMonth(from);
      break;
    default:
      if (!to) return [{ field: 'to', message: 'required for CUSTOM' }];
      expected = to;
  }
  if (to !== null && to !== expected) return [{ field: 'to', message: `must be ${expected} for ${granularity}` }];
  if (expected < from) return [{ field: 'to', message: 'must not be before from' }];
  if (daysBetween(from, expected) > 366) return [{ field: 'to', message: 'at most 366 days' }];
  return { from, to: expected };
}

/** [range] minus the union of [covered], as sorted ranges. */
function gaps(range: DateRange, covered: DateRange[]): DateRange[] {
  const out: DateRange[] = [];
  let cursor = range.from;
  for (const c of [...covered].sort((a, b) => a.from.localeCompare(b.from))) {
    if (c.to < cursor || c.from > range.to) continue;
    if (c.from > cursor) out.push({ from: cursor, to: addDays(c.from, -1) });
    if (c.to >= cursor) cursor = addDays(c.to, 1);
    if (cursor > range.to) return out;
  }
  if (cursor <= range.to) out.push({ from: cursor, to: range.to });
  return out;
}

const cents = (m: string) => Math.round(Number(m) * 100);
const money = (c: number) => (c / 100).toFixed(2);
const ratio = (part: number, whole: number) => (whole === 0 ? 0 : Math.round((part / whole) * 1000) / 1000);

export function mockCoverage(range: DateRange, accounts: Account[], spans: CoveredSpan[], txns: ReportTxn[]): Coverage {
  const result = accounts.flatMap((a) => {
    const from = a.activeFrom > range.from ? a.activeFrom : range.from;
    const to = a.closedAt && a.closedAt < range.to ? a.closedAt : range.to;
    if (to < from) return [];
    const g = gaps({ from, to }, spans.filter((s) => s.accountId === a.id));
    return [{ accountId: a.id, accountLabel: a.label, activeFrom: a.activeFrom, closedAt: a.closedAt ?? null, status: g.length ? 'PARTIAL' as const : 'COMPLETE' as const, gaps: g }];
  });
  const unconfirmed = txns.filter((t) => t.transferStatus === 'CANDIDATE' && t.date >= range.from && t.date <= range.to);
  const amount = unconfirmed.reduce((s, t) => s + Math.abs(cents(t.amount)), 0);
  return {
    from: range.from,
    to: range.to,
    status: result.every((a) => a.status === 'COMPLETE') && unconfirmed.length === 0 ? 'COMPLETE' : 'PARTIAL',
    accounts: result,
    unconfirmedTransfers: { count: unconfirmed.length, amountBase: money(amount) },
  };
}

/** "PLATA CARD ****1111 KAUFLAND IASI RO" → "Kaufland". */
function merchantName(description: string): string {
  const words = description
    .toUpperCase()
    .replace(/^(PLATA CARD|POS|CUMPARARE POS|PLATA)\s+/, '')
    .split(/\s+/)
    .filter((w) => /^[A-Z][A-Z.&-]+$/.test(w));
  const name = words[0] ?? description.split(/\s+/)[0] ?? '?';
  return name.charAt(0) + name.slice(1).toLowerCase();
}

function totalsOf(txns: ReportTxn[]) {
  const counted = txns.filter((t) => t.transferStatus === 'NONE' || t.transferStatus === 'EXTERNAL');
  const spend = counted.filter((t) => t.amount.startsWith('-'));
  const income = counted.filter((t) => !t.amount.startsWith('-') && cents(t.amount) !== 0);
  return {
    spend,
    income,
    spendCents: spend.reduce((s, t) => s - cents(t.amount), 0),
    incomeCents: income.reduce((s, t) => s + cents(t.amount), 0),
  };
}

export function mockReport(
  id: string,
  granularity: Granularity,
  range: DateRange,
  accountIds: string[] | null,
  accounts: Account[],
  spans: CoveredSpan[],
  allTxns: ReportTxn[],
): Report {
  const scoped = accounts.filter((a) => !accountIds || accountIds.includes(a.id));
  const inScope = allTxns.filter((t) => scoped.some((a) => a.id === t.accountId));
  const current = inScope.filter((t) => t.date >= range.from && t.date <= range.to);
  const coverage = mockCoverage(range, scoped, spans, inScope);
  const { spend, spendCents, incomeCents, income } = totalsOf(current);
  const days = daysBetween(range.from, range.to);
  const monthScale = days >= 28;

  const byCat = new Map<Category, { cents: number; count: number }>();
  for (const t of spend) {
    const c = t.category ?? 'OTHER';
    const v = byCat.get(c) ?? { cents: 0, count: 0 };
    byCat.set(c, { cents: v.cents - cents(t.amount), count: v.count + 1 });
  }
  const byCategory: ReportCategoryTotal[] = [...byCat.entries()]
    .sort((a, b) => b[1].cents - a[1].cents || a[0].localeCompare(b[0]))
    .map(([category, v]) => ({ category, amountBase: money(v.cents), count: v.count, share: ratio(v.cents, spendCents) }));

  const byMerchant = new Map<string, { cents: number; count: number; category: Category | null }>();
  for (const t of spend) {
    const name = merchantName(t.description);
    const v = byMerchant.get(name) ?? { cents: 0, count: 0, category: t.category };
    byMerchant.set(name, { cents: v.cents - cents(t.amount), count: v.count + 1, category: v.category });
  }
  const topMerchants: MerchantTotal[] = [...byMerchant.entries()]
    .sort((a, b) => b[1].cents - a[1].cents || a[0].localeCompare(b[0]))
    .slice(0, 10)
    .map(([name, v]) => ({ merchantId: null, name, category: v.category, amountBase: money(v.cents), count: v.count }));

  const trend: TrendPoint[] = [];
  if (days > 1) {
    for (let d = range.from; d <= range.to; d = addDays(d, 1)) {
      const t = totalsOf(current.filter((x) => x.date === d));
      trend.push({ from: d, to: d, spendBase: money(t.spendCents), incomeBase: money(t.incomeCents) });
    }
  }
  const history: TrendPoint[] = [];
  if (granularity === 'MONTH') {
    for (let k = 5; k >= 0; k--) {
      const from = addMonths(range.from, -k);
      const to = endOfMonth(from);
      const t = totalsOf(inScope.filter((x) => x.date >= from && x.date <= to));
      history.push({ from, to, spendBase: money(t.spendCents), incomeBase: money(t.incomeCents), coverage: mockCoverage({ from, to }, scoped, spans, inScope).status });
    }
  }

  const insights: Insight[] = [];
  const fees = byCategory.filter((c) => c.category === 'BANK_FEES' || c.category === 'CARD_INTEREST' || c.category === 'FX_FEES');
  if (days >= 2 && fees.length) {
    const total = fees.reduce((s, c) => s + cents(c.amountBase), 0);
    insights.push({
      type: 'FEES_AND_INTEREST',
      severity: fees.some((c) => c.category === 'CARD_INTEREST') ? 'WARNING' : 'INFO',
      message: `You paid ${money(total)} RON in fees and interest`,
      data: { totalBase: money(total), items: fees.map((c) => ({ category: c.category, amountBase: c.amountBase, count: c.count })) },
    });
  }
  const savingsRate = monthScale && incomeCents > 0 ? Math.round(((incomeCents - spendCents) / incomeCents) * 1000) / 1000 : null;
  if (savingsRate !== null) {
    insights.push({
      type: 'SAVINGS_RATE',
      severity: savingsRate < 0 ? 'WARNING' : 'INFO',
      message: savingsRate < 0
        ? `You spent ${money(spendCents - incomeCents)} RON more than you earned`
        : `You kept ${Math.round(savingsRate * 100)}% of your income (${money(incomeCents - spendCents)} RON)`,
      data: { rate: savingsRate, incomeBase: money(incomeCents), spendBase: money(spendCents), savingsDepositsBase: '0.00' },
    });
  }
  insights.sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'WARNING' ? -1 : 1));

  return {
    id,
    granularity,
    periodStart: range.from,
    periodEnd: range.to,
    accountIds,
    status: 'READY',
    stale: false,
    version: 1,
    builtAt: new Date().toISOString(),
    currency: 'RON',
    summary: {
      coverage,
      totals: {
        spendBase: money(spendCents),
        incomeBase: money(incomeCents),
        netBase: money(incomeCents - spendCents),
        savingsDepositsBase: '0.00',
        savingsRate,
        transactionCount: spend.length + income.length,
      },
      unconfirmedTransfers: coverage.unconfirmedTransfers,
      byCategory,
      byAccount: scoped
        .filter((a) => coverage.accounts.some((c) => c.accountId === a.id) || current.some((t) => t.accountId === a.id))
        .map((a) => {
          const t = totalsOf(current.filter((x) => x.accountId === a.id));
          return { accountId: a.id, accountLabel: a.label, spendBase: money(t.spendCents), incomeBase: money(t.incomeCents) };
        }),
      topMerchants,
      trend,
      history,
      baseline: { periodsWanted: granularity === 'MONTH' || granularity === 'CUSTOM' ? 3 : 4, periodsRequired: 2, periodsUsed: 0, enoughHistory: false },
    },
    insights,
  };
}
