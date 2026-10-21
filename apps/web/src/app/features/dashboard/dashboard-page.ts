import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Params, Router, RouterLink } from '@angular/router';
import { NgxEchartsDirective } from 'ngx-echarts';
import { Subscription, catchError, of, timer } from 'rxjs';
import {
  Account,
  AccountsApi,
  DateRange,
  GRANULARITIES,
  Granularity,
  Insight,
  InsightType,
  Report,
  ReportsApi,
  StatementsApi,
  handledErrors,
} from '../../core/api';
import { APP_ENV } from '../../core/config/app-env';
import { OpenQuestionsStore } from '../../core/state/open-questions.store';
import { CategoryChip } from '../../shared/category-chip';
import { EmptyState } from '../../shared/empty-state';
import { MoneyPipe } from '../../shared/money.pipe';
import { categoryBars, dailySpend, monthlyHistory } from './dashboard-charts';
import { periodFor, periodLabel, rangeLabel, shift, shortMonthLabel, today } from './period';

const SILENT = handledErrors(0, 404, 500, 502, 503, 504);

const INSIGHT_ICONS: Record<InsightType, string> = {
  CATEGORY_SPIKE: 'trending_up',
  UNUSUAL_TRANSACTION: 'priority_high',
  MERCHANT_CONCENTRATION: 'storefront',
  FEES_AND_INTEREST: 'account_balance',
  RECURRING_PAYMENTS: 'autorenew',
  DUPLICATE_SUBSCRIPTIONS: 'content_copy',
  SAVINGS_RATE: 'savings',
};

const GRANULARITY_LABELS: Record<Granularity, string> = { DAY: 'Day', WEEK: 'Week', MONTH: 'Month', CUSTOM: 'Custom' };

/**
 * Period overview (plan section 14): day / week / month / custom range, all accounts or a selection, kept in the URL
 * (`?g=MONTH&from=2026-08-01&to=&accounts=id,id`). A stale report shows its previous numbers with "Updating…" and
 * is polled until the rebuild lands; a partial period shows which account days are missing.
 */
@Component({
  selector: 'app-dashboard-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink, MatButtonModule, MatButtonToggleModule, MatCardModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressBarModule, MatSelectModule, MatTooltipModule, NgxEchartsDirective, CategoryChip, EmptyState, MoneyPipe,
  ],
  templateUrl: './dashboard-page.html',
  styleUrl: './dashboard-page.scss',
})
export class DashboardPage {
  private readonly api = inject(ReportsApi);
  private readonly accountsApi = inject(AccountsApi);
  private readonly statementsApi = inject(StatementsApi);
  private readonly router = inject(Router);
  private readonly pollMs = inject(APP_ENV).pollIntervalMs;
  protected readonly questions = inject(OpenQuestionsStore);
  private request$?: Subscription;
  private poll$?: Subscription;

  // Query parameters (bound by withComponentInputBinding).
  readonly g = input<string | undefined>(undefined);
  readonly from = input<string | undefined>(undefined);
  readonly to = input<string | undefined>(undefined);
  readonly accounts = input<string | undefined>(undefined);

  protected readonly granularities = GRANULARITIES.map((value) => ({ value, label: GRANULARITY_LABELS[value] }));
  protected readonly accountList = signal<Account[]>([]);
  protected readonly report = signal<Report | null>(null);
  protected readonly loading = signal(false);
  protected readonly failed = signal(false);
  /** Default period (latest categorized statement's month) once known, used when the URL has none. */
  private readonly defaultAnchor = signal<string | null>(null);

  readonly granularity = computed<Granularity>(() =>
    GRANULARITIES.includes(this.g() as Granularity) ? (this.g() as Granularity) : 'MONTH',
  );
  readonly accountIds = computed(() => (this.accounts() ?? '').split(',').filter((id) => /^[0-9a-f-]{36}$/.test(id)).sort());
  readonly range = computed<DateRange | null>(() => {
    const anchor = isDate(this.from()) ? this.from()! : this.defaultAnchor();
    if (!anchor) return null;
    return periodFor(this.granularity(), anchor, isDate(this.to()) ? this.to() : undefined);
  });
  protected readonly title = computed(() => {
    const r = this.range();
    return r ? periodLabel(this.granularity(), r) : '';
  });

  protected readonly summary = computed(() => this.report()?.summary ?? null);
  protected readonly isEmpty = computed(() => {
    const s = this.summary();
    return !!s && s.totals.transactionCount === 0 && s.coverage.accounts.every((a) => a.gaps.length > 0 && a.gaps[0].from === s.coverage.from && a.gaps[0].to === s.coverage.to);
  });
  protected readonly coverageGaps = computed(() =>
    (this.summary()?.coverage.accounts ?? []).filter((a) => a.gaps.length > 0)
      .map((a) => `${a.accountLabel}: ${a.gaps.map((g) => rangeLabel(g)).join(', ')}`),
  );
  protected readonly insights = computed(() => this.report()?.insights ?? []);
  protected readonly categoryChart = computed(() => categoryBars(this.summary()?.byCategory ?? []));
  protected readonly trendChart = computed(() => dailySpend(this.summary()?.trend ?? []));
  protected readonly historyChart = computed(() => monthlyHistory(this.summary()?.history ?? []));
  protected readonly showSavingsRate = computed(() => this.summary()?.totals.savingsRate !== null && this.summary()?.totals.savingsRate !== undefined);

  constructor() {
    effect(() => {
      const range = this.range();
      const granularity = this.granularity();
      const accountIds = this.accountIds();
      if (range) untracked(() => this.load(granularity, range, accountIds));
    });
    this.accountsApi.listAccounts({ context: SILENT }).pipe(catchError(() => of([]))).subscribe((a) => this.accountList.set(a));
    this.statementsApi.listStatements({ status: 'CATEGORIZED' }, { context: SILENT }).pipe(catchError(() => of([]))).subscribe((statements) => {
      const latest = statements.map((s) => s.periodEnd).filter((d): d is string => !!d).sort().pop();
      this.defaultAnchor.set(latest ?? today());
    });
    this.questions.refresh();
    inject(DestroyRef).onDestroy(() => {
      this.request$?.unsubscribe();
      this.poll$?.unsubscribe();
    });
  }

  load(granularity: Granularity, range: DateRange, accountIds: string[]): void {
    this.request$?.unsubscribe();
    this.poll$?.unsubscribe();
    this.loading.set(true);
    this.failed.set(false);
    this.request$ = this.api
      .getReport({ granularity, from: range.from, to: granularity === 'CUSTOM' ? range.to : undefined, accountIds })
      .subscribe({
        next: (report) => this.show(report),
        error: () => {
          this.report.set(null);
          this.loading.set(false);
          this.failed.set(true);
        },
      });
  }

  /** Shows a report; a stale or pending one is polled by ID until it is rebuilt. */
  private show(report: Report): void {
    this.report.set(report);
    this.loading.set(false);
    if (report.stale || report.status === 'PENDING') {
      this.poll$ = timer(this.pollMs).subscribe(() => {
        this.request$ = this.api.getReportById(report.id, { context: SILENT }).subscribe({
          next: (next) => this.show(next),
          error: () => undefined,
        });
      });
    }
  }

  setGranularity(value: Granularity): void {
    const range = this.range();
    if (!range) return;
    const next = periodFor(value, range.from, value === 'CUSTOM' ? range.to : undefined);
    this.navigate({ g: value === 'MONTH' ? null : value, from: next.from, to: value === 'CUSTOM' ? next.to : null });
  }

  step(direction: -1 | 1): void {
    const range = this.range();
    if (!range) return;
    const next = shift(this.granularity(), range, direction);
    this.navigate({ from: next.from, to: this.granularity() === 'CUSTOM' ? next.to : null });
  }

  setDate(field: 'from' | 'to', value: string): void {
    if (!isDate(value)) return;
    const range = this.range();
    if (field === 'to' && range && value < range.from) return;
    this.navigate({ [field]: value });
  }

  setAccounts(ids: string[]): void {
    this.navigate({ accounts: ids.length ? [...ids].sort().join(',') : null });
  }

  retry(): void {
    const range = this.range();
    if (range) this.load(this.granularity(), range, this.accountIds());
  }

  protected icon(insight: Insight): string {
    return INSIGHT_ICONS[insight.type] ?? 'lightbulb';
  }

  protected percent(ratio: number | null | undefined): string {
    return ratio === null || ratio === undefined ? '' : `${Math.round(ratio * 100)}%`;
  }

  protected monthLabel(date: string): string {
    return shortMonthLabel(date);
  }

  /** Transactions page link for the current period and accounts. */
  protected transactionsLink(): Params {
    const r = this.range();
    const ids = this.accountIds();
    return { from: r?.from, to: r?.to, accountId: ids.length === 1 ? ids[0] : null };
  }

  private navigate(params: Params): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}

function isDate(value: string | null | undefined): boolean {
  return !!value && /^\d{4}-\d{2}-\d{2}$/.test(value);
}
