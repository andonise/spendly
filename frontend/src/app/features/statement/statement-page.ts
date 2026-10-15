import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Account, AccountsApi, Statement, StatementStatus, StatementsApi, handledErrors } from '../../core/api';
import { StatementEventsService } from '../../core/sse/statement-events.service';
import { OpenQuestionsStore } from '../../core/state/open-questions.store';
import { EmptyState } from '../../shared/empty-state';
import { Progress } from '../../shared/progress';
import { StatusChip } from '../../shared/status-chip';
import { QuestionsForm } from '../questions/questions-form';
import { StatementSummary } from './statement-summary';

/**
 * Status-driven statement page (plan section 14). The screen is derived only from `status`,
 * which comes from GET /api/statements/{id} on load (so a reload restores the same view,
 * AC-HIL-3) and then from StatementEventsService (SSE, or polling as a fallback / in mock mode).
 */
@Component({
  selector: 'app-statement-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink, MatButtonModule, MatCardModule, MatIconModule, EmptyState, Progress, StatusChip,
    QuestionsForm, StatementSummary,
  ],
  templateUrl: './statement-page.html',
  styleUrl: './statement-page.scss',
})
export class StatementPage {
  private readonly statementsApi = inject(StatementsApi);
  private readonly accountsApi = inject(AccountsApi);
  private readonly events = inject(StatementEventsService);
  private readonly openQuestions = inject(OpenQuestionsStore);
  private watching?: Subscription;
  private refreshing?: Subscription;

  /** Route parameter `:id` (bound by withComponentInputBinding). */
  readonly id = input.required<string>();

  readonly statement = signal<Statement | null>(null);
  readonly status = signal<StatementStatus | null>(null);
  protected readonly account = signal<Account | null>(null);
  protected readonly notFound = signal(false);
  protected readonly retrying = signal(false);

  protected readonly progressDetail = computed(() => {
    const p = this.statement()?.progress;
    if (!p?.transactionsImported) return null;
    const categorized = p.transactionsCategorized ?? 0;
    return categorized > 0
      ? `${categorized} of ${p.transactionsImported} transactions categorized`
      : `${p.transactionsImported} transactions imported`;
  });

  constructor() {
    // Runs on first render and again if the router reuses this component for another :id.
    effect(() => {
      this.id();
      untracked(() => {
        this.watching?.unsubscribe();
        this.statement.set(null);
        this.status.set(null);
        this.notFound.set(false);
        this.load();
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.watching?.unsubscribe();
      this.refreshing?.unsubscribe();
    });
  }

  /** Loads the statement (and its account), then follows its status. */
  load(): void {
    this.statementsApi.getStatement(this.id(), { context: handledErrors(404) }).subscribe({
      next: (statement) => {
        this.apply(statement);
        if (!this.account() || this.account()!.id !== statement.accountId) {
          this.accountsApi.getAccount(statement.accountId, { context: handledErrors(404) })
            .subscribe({ next: (a) => this.account.set(a), error: () => undefined });
        }
        this.follow();
      },
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 404) this.notFound.set(true);
      },
    });
  }

  onAnswered(statement: Statement): void {
    this.apply(statement);
    this.follow();
  }

  onConflict(): void {
    this.load();
  }

  retry(): void {
    if (this.retrying()) return;
    this.retrying.set(true);
    this.statementsApi.retryStatement(this.id()).subscribe({
      next: (statement) => {
        this.retrying.set(false);
        this.apply(statement);
        this.follow();
      },
      error: () => {
        this.retrying.set(false);
        this.load(); // e.g. 409: someone else already retried; show the real status
      },
    });
  }

  /** (Re)subscribes to status events; each change refreshes the statement for progress/summary/error. */
  private follow(): void {
    this.watching?.unsubscribe();
    this.watching = this.events.watch(this.id()).subscribe((event) => {
      if (event.status !== this.status()) {
        this.status.set(event.status);
        this.refresh();
      }
    });
  }

  private refresh(): void {
    this.refreshing?.unsubscribe();
    this.refreshing = this.statementsApi.getStatement(this.id()).subscribe((s) => {
      this.apply(s);
      if (s.status === 'CATEGORIZED' || s.status === 'NEEDS_INPUT') this.openQuestions.refresh();
    });
  }

  private apply(statement: Statement): void {
    this.statement.set(statement);
    this.status.set(statement.status);
  }
}
