import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import {
  Answer,
  Category,
  CounterpartyRef,
  Question,
  QuestionsApi,
  TransactionsApi,
  handledErrors,
} from '../../core/api';
import { OpenQuestionsStore } from '../../core/state/open-questions.store';
import { CATEGORY_OPTIONS, categoryLabel } from '../../shared/categories';
import { EmptyState } from '../../shared/empty-state';
import { MoneyPipe } from '../../shared/money.pipe';
import { counterpartyName } from '../../shared/transfers';

interface StatementGroup {
  statementId: string;
  accountLabel: string;
  questions: Question[];
}

interface CardState {
  /** "No" was pressed: show the category picker. */
  picking: boolean;
  applyToFuture: boolean;
  busy: boolean;
}

const IDLE: CardState = { picking: false, applyToFuture: false, busy: false };

/**
 * All open questions (GET /api/questions?status=OPEN), linked from the toolbar badge.
 * Statement questions are answered on their statement page (one submission per statement);
 * TRANSFER questions (not tied to a statement) are answered here, one request per card.
 */
@Component({
  selector: 'app-questions-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink, MatButtonModule, MatCardModule, MatCheckboxModule, MatFormFieldModule, MatIconModule,
    MatProgressBarModule, MatSelectModule, EmptyState, MoneyPipe,
  ],
  templateUrl: './questions-page.html',
  styleUrl: './questions-page.scss',
})
export class QuestionsPage implements OnInit {
  private readonly api = inject(QuestionsApi);
  private readonly transactionsApi = inject(TransactionsApi);
  private readonly store = inject(OpenQuestionsStore);

  /** `?focus=<questionId>` (from an "Unconfirmed transfer" badge): highlight that card. */
  readonly focus = input<string | undefined>(undefined);

  protected readonly label = categoryLabel;
  /** "No" means not a transfer, so INTERNAL_TRANSFER is not offered there. */
  protected readonly noOptions = CATEGORY_OPTIONS.filter((c) => c.value !== 'INTERNAL_TRANSFER');

  protected readonly loading = signal(true);
  protected readonly questions = signal<Question[]>([]);
  protected readonly states = signal<Record<string, CardState>>({});
  /** Counterparty of each TRANSFER question's transaction (undefined = not loaded yet). */
  protected readonly counterparties = signal<Record<string, CounterpartyRef | null>>({});
  protected readonly notice = signal<string | null>(null);

  protected readonly statementGroups = computed<StatementGroup[]>(() => {
    const groups = new Map<string, StatementGroup>();
    for (const q of this.questions()) {
      if (!q.statementId) continue;
      const g = groups.get(q.statementId) ?? {
        statementId: q.statementId,
        accountLabel: q.transaction.accountLabel ?? 'Statement',
        questions: [],
      };
      g.questions.push(q);
      groups.set(q.statementId, g);
    }
    return [...groups.values()];
  });

  protected readonly transferQuestions = computed(() => this.questions().filter((q) => !q.statementId));

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.listOpenQuestions({ status: 'OPEN' }).subscribe({
      next: (questions) => {
        const open = questions.filter((q) => q.status === 'OPEN');
        this.questions.set(open);
        this.states.set(Object.fromEntries(open.map((q) => [q.id, { ...IDLE }])));
        this.loading.set(false);
        for (const q of open) if (!q.statementId) this.loadCounterparty(q);
      },
      error: () => this.loading.set(false),
    });
  }

  state(id: string): CardState {
    return this.states()[id] ?? IDLE;
  }

  protected counterpartyText(cp: CounterpartyRef): string {
    return counterpartyName(cp);
  }

  setApplyToFuture(id: string, applyToFuture: boolean): void {
    this.patch(id, { applyToFuture });
  }

  /** Yes, it's my transfer → INTERNAL_TRANSFER (+ applyToFuture when the box is ticked). */
  yes(q: Question): void {
    const withFuture = this.state(q.id).applyToFuture && !!this.counterparties()[q.id];
    this.answer(q, withFuture ? { category: 'INTERNAL_TRANSFER', applyToFuture: true } : { category: 'INTERNAL_TRANSFER' });
  }

  /** No → show the category picker. */
  no(q: Question): void {
    this.patch(q.id, { picking: true });
  }

  /** A category after "No" → EXTERNAL with that category. */
  pick(q: Question, category: Category): void {
    this.answer(q, { category });
  }

  skip(q: Question): void {
    this.answer(q, { skip: true });
  }

  protected noSuggestions(q: Question): Category[] {
    return q.suggestions.filter((c) => c !== 'INTERNAL_TRANSFER');
  }

  private answer(q: Question, body: Answer): void {
    if (this.state(q.id).busy) return; // one request per card, even on double click
    this.patch(q.id, { busy: true });
    this.notice.set(null);
    this.api.answerQuestion(q.id, body, { context: handledErrors(409) }).subscribe({
      next: () => {
        this.questions.update((all) => all.filter((x) => x.id !== q.id));
        this.notice.set(
          body.skip ? 'Skipped: filed under Other.'
            : body.category === 'INTERNAL_TRANSFER' ? 'Saved: marked as an internal transfer.'
            : `Saved: not a transfer (${categoryLabel(body.category)}).`,
        );
        this.store.refresh();
      },
      error: (error: unknown) => {
        this.patch(q.id, { busy: false });
        if (error instanceof HttpErrorResponse && error.status === 409) {
          this.notice.set('That question was already answered (maybe in another tab). The list has been refreshed.');
          this.store.refresh();
          this.load();
        }
        // Other errors were shown by the error interceptor; the card stays so the user can retry.
      },
    });
  }

  private loadCounterparty(q: Question): void {
    // QuestionTransaction has no counterparty in the contract yet, so read it from the transaction.
    this.transactionsApi
      .getTransaction(q.transaction.id, { context: handledErrors(0, 404, 500, 502, 503, 504) })
      .subscribe({
        next: (t) => this.counterparties.update((m) => ({ ...m, [q.id]: t.counterparty ?? null })),
        error: () => this.counterparties.update((m) => ({ ...m, [q.id]: null })),
      });
  }

  private patch(id: string, change: Partial<CardState>): void {
    this.states.update((all) => ({ ...all, [id]: { ...(all[id] ?? IDLE), ...change } }));
  }
}
