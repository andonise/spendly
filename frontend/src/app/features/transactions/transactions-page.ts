import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Params, Router, RouterLink } from '@angular/router';
import { Subscription, catchError, of } from 'rxjs';
import {
  Account,
  AccountsApi,
  CATEGORIES,
  Category,
  QuestionsApi,
  Statement,
  StatementsApi,
  TRANSACTION_SORTS,
  TRANSFER_STATUSES,
  Transaction,
  TransactionPage,
  TransactionQuery,
  TransactionSort,
  TransactionsApi,
  TransferStatus,
  handledErrors,
} from '../../core/api';
import { CATEGORY_OPTIONS } from '../../shared/categories';
import { CategoryChip } from '../../shared/category-chip';
import { EmptyState } from '../../shared/empty-state';
import { MoneyPipe } from '../../shared/money.pipe';
import { TRANSFER_STATUS_LABELS, categoryProvenance, counterpartyName } from '../../shared/transfers';
import { CounterpartyDialog, CounterpartyDialogData } from '../counterparties/counterparty-dialog';

/** Filter keys kept in the URL query string. */
export const FILTER_KEYS = ['accountId', 'statementId', 'from', 'to', 'category', 'transferStatus'] as const;
export type FilterKey = (typeof FILTER_KEYS)[number];

const DEFAULT_SIZE = 50;
const SILENT = handledErrors(0, 404, 500, 502, 503, 504);

/**
 * Transactions across all accounts (plan section 14): server-side paging, sorting and filters,
 * all synced to the URL (`?accountId=&statementId=&from=&to=&category=&transferStatus=&page=&size=&sort=`).
 *
 * The "Transfer" badge on an INTERNAL row opens its pair: the page fetches the paired transaction
 * and navigates to `?accountId=<its account>&from=<its date>&to=<its date>&highlight=<its id>`
 * (other filters cleared), so the pair is guaranteed to be on the first page, and highlights it.
 */
@Component({
  selector: 'app-transactions-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatPaginatorModule,
    MatProgressBarModule, MatSelectModule, MatSortModule, MatTableModule, MatTooltipModule, CategoryChip,
    EmptyState, MoneyPipe,
  ],
  templateUrl: './transactions-page.html',
  styleUrl: './transactions-page.scss',
})
export class TransactionsPage {
  private readonly api = inject(TransactionsApi);
  private readonly accountsApi = inject(AccountsApi);
  private readonly statementsApi = inject(StatementsApi);
  private readonly questionsApi = inject(QuestionsApi);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private loading$?: Subscription;

  // Query parameters (bound by withComponentInputBinding).
  readonly accountId = input<string | undefined>(undefined);
  readonly statementId = input<string | undefined>(undefined);
  readonly from = input<string | undefined>(undefined);
  readonly to = input<string | undefined>(undefined);
  readonly category = input<string | undefined>(undefined);
  readonly transferStatus = input<string | undefined>(undefined);
  readonly page = input<string | undefined>(undefined);
  readonly size = input<string | undefined>(undefined);
  readonly sort = input<string | undefined>(undefined);
  /** Id of a row to highlight (the other side of a transfer). */
  readonly highlight = input<string | undefined>(undefined);

  protected readonly columns = ['bookingDate', 'account', 'description', 'amount', 'category', 'transfer', 'counterparty', 'actions'];
  protected readonly categoryOptions = CATEGORY_OPTIONS;
  protected readonly transferOptions = TRANSFER_STATUSES.map((value) => ({ value, label: TRANSFER_STATUS_LABELS[value] }));
  protected readonly provenance = categoryProvenance;

  protected readonly result = signal<TransactionPage | null>(null);
  protected readonly loading = signal(true);
  protected readonly accounts = signal<Account[]>([]);
  protected readonly statements = signal<Statement[]>([]);
  /** transaction id → id of its open TRANSFER question (for "Unconfirmed transfer" links). */
  protected readonly questionByTxn = signal<Map<string, string>>(new Map());

  /** The validated query sent to the API. */
  readonly query = computed<TransactionQuery>(() => {
    const q: TransactionQuery = {};
    if (this.accountId()) q.accountId = this.accountId();
    if (this.statementId()) q.statementId = this.statementId();
    if (isDate(this.from())) q.from = this.from();
    if (isDate(this.to())) q.to = this.to();
    if (CATEGORIES.includes(this.category() as Category)) q.category = this.category() as Category;
    if (TRANSFER_STATUSES.includes(this.transferStatus() as TransferStatus)) q.transferStatus = this.transferStatus() as TransferStatus;
    q.page = Math.max(0, toInt(this.page(), 0));
    q.size = Math.min(200, Math.max(1, toInt(this.size(), DEFAULT_SIZE)));
    q.sort = TRANSACTION_SORTS.includes(this.sort() as TransactionSort) ? (this.sort() as TransactionSort) : 'bookingDate,desc';
    return q;
  });

  protected readonly sortActive = computed(() => this.query().sort!.split(',')[0]);
  protected readonly sortDirection = computed(() => this.query().sort!.split(',')[1] as 'asc' | 'desc');
  protected readonly hasFilters = computed(() => FILTER_KEYS.some((k) => !!this.query()[k]));
  private readonly accountsById = computed(() => new Map(this.accounts().map((a) => [a.id, a])));

  constructor() {
    effect(() => {
      const query = this.query();
      untracked(() => this.load(query));
    });
    this.loadLookups();
    inject(DestroyRef).onDestroy(() => this.loading$?.unsubscribe());
  }

  load(query: TransactionQuery = this.query()): void {
    this.loading$?.unsubscribe();
    this.loading.set(true);
    this.loading$ = this.api.listTransactions(query).subscribe({
      next: (page) => {
        this.result.set(page);
        this.loading.set(false);
      },
      error: () => {
        this.result.set(null);
        this.loading.set(false);
      },
    });
  }

  /** Sets one filter in the URL and goes back to the first page. */
  setFilter(key: FilterKey, value: string | null | undefined): void {
    this.navigate({ [key]: value || null, page: null, highlight: null });
  }

  clearFilters(): void {
    this.navigate({ ...Object.fromEntries(FILTER_KEYS.map((k) => [k, null])), page: null, highlight: null });
  }

  onSort(sort: Sort): void {
    const value = sort.direction ? `${sort.active},${sort.direction}` : null;
    this.navigate({ sort: value === 'bookingDate,desc' ? null : value, page: null });
  }

  onPage(event: PageEvent): void {
    this.navigate({ page: event.pageIndex || null, size: event.pageSize === DEFAULT_SIZE ? null : event.pageSize });
  }

  /** "Transfer" badge: show the other side of the pair (see class comment). */
  openPair(t: Transaction): void {
    if (!t.pairedTransactionId) return;
    this.api.getTransaction(t.pairedTransactionId).subscribe((pair) => {
      void this.router.navigate(['/transactions'], {
        queryParams: { accountId: pair.accountId, from: pair.bookingDate, to: pair.bookingDate, highlight: pair.id },
      });
    });
  }

  /** "Label counterparty" row action. */
  labelCounterparty(t: Transaction): void {
    if (!t.counterparty) return;
    const data: CounterpartyDialogData = {
      counterparty: t.counterparty,
      example: t.description,
      ownAccountLabel: t.counterparty.ownAccountId ? (this.accountsById().get(t.counterparty.ownAccountId)?.label ?? null) : null,
    };
    this.dialog
      .open(CounterpartyDialog, { data, width: '440px', maxWidth: 'calc(100vw - 32px)' })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.load();
          this.loadQuestions();
        }
      });
  }

  protected accountLabel(t: Transaction): string {
    return t.accountLabel ?? this.accountsById().get(t.accountId)?.label ?? 'Unknown account';
  }

  protected counterpartyLabel(t: Transaction): string {
    const cp = t.counterparty;
    if (!cp) return '';
    return counterpartyName(cp, cp.ownAccountId ? this.accountsById().get(cp.ownAccountId)?.label : null);
  }

  protected statementLabel(s: Statement): string {
    const account = this.accountsById().get(s.accountId)?.label ?? 'Statement';
    return s.periodStart ? `${account} · ${s.periodStart} – ${s.periodEnd}` : `${account} · ${s.fileType}`;
  }

  private navigate(params: Params): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }

  private loadLookups(): void {
    // Lookups only fill the filter menus; the table works without them.
    this.accountsApi.listAccounts({ context: SILENT }).pipe(catchError(() => of([]))).subscribe((a) => this.accounts.set(a));
    this.statementsApi.listStatements({}, { context: SILENT }).pipe(catchError(() => of([]))).subscribe((s) => this.statements.set(s));
    this.loadQuestions();
  }

  private loadQuestions(): void {
    this.questionsApi
      .listOpenQuestions({ status: 'OPEN' }, { context: SILENT })
      .pipe(catchError(() => of([])))
      .subscribe((qs) =>
        this.questionByTxn.set(new Map(qs.filter((q) => q.kind === 'TRANSFER').map((q) => [q.transaction.id, q.id]))),
      );
  }
}

function toInt(value: string | undefined, fallback: number): number {
  const n = Number(value);
  return value !== undefined && value !== '' && Number.isInteger(n) ? n : fallback;
}

function isDate(value: string | undefined): boolean {
  return !!value && /^\d{4}-\d{2}-\d{2}$/.test(value);
}
