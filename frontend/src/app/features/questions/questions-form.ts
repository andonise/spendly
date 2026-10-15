import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import {
  AnswerItem,
  AnswersSubmission,
  Category,
  Question,
  QuestionsApi,
  Statement,
  handledErrors,
} from '../../core/api';
import { OpenQuestionsStore } from '../../core/state/open-questions.store';
import { CATEGORY_OPTIONS, categoryLabel } from '../../shared/categories';
import { MoneyPipe } from '../../shared/money.pipe';

export interface AnswerDraft {
  category: Category | null;
  skip: boolean;
  hint: string;
  applyToFuture: boolean;
}

const EMPTY_DRAFT: AnswerDraft = { category: null, skip: false, hint: '', applyToFuture: false };

export function isDraftDone(d: AnswerDraft | undefined): boolean {
  return !!d && (d.skip || d.category !== null);
}

/**
 * Human-in-the-loop form (plan section 14): one card per open question. Submit is enabled only
 * when every card is answered or skipped, sends one AnswersSubmission, and is protected against
 * double clicks. A 409 (already submitted) shows a friendly message and emits `conflict` so the
 * page reloads the status.
 */
@Component({
  selector: 'app-questions-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule, MatCardModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressBarModule, MatSelectModule, MoneyPipe,
  ],
  templateUrl: './questions-form.html',
  styleUrl: './questions-form.scss',
})
export class QuestionsForm implements OnInit {
  private readonly api = inject(QuestionsApi);
  private readonly openQuestions = inject(OpenQuestionsStore);

  readonly statementId = input.required<string>();
  /** Answers accepted (202); carries the statement, now CATEGORIZING. */
  readonly submitted = output<Statement>();
  /** 409: the statement no longer accepts answers; the parent should reload its status. */
  readonly conflict = output<void>();

  protected readonly categoryOptions = CATEGORY_OPTIONS;
  protected readonly label = categoryLabel;

  protected readonly questions = signal<Question[]>([]);
  protected readonly drafts = signal<Record<string, AnswerDraft>>({});
  protected readonly loading = signal(true);
  protected readonly submitting = signal(false);
  protected readonly done = signal(false);
  protected readonly conflictMessage = signal<string | null>(null);

  protected readonly answeredCount = computed(
    () => this.questions().filter((q) => isDraftDone(this.drafts()[q.id])).length,
  );
  readonly allAnswered = computed(
    () => this.questions().length > 0 && this.answeredCount() === this.questions().length,
  );
  readonly canSubmit = computed(
    () => this.allAnswered() && !this.submitting() && !this.done() && !this.conflictMessage(),
  );

  ngOnInit(): void {
    this.api.getStatementQuestions(this.statementId()).subscribe({
      next: (questions) => {
        const open = questions.filter((q) => q.status === 'OPEN');
        this.questions.set(open);
        this.drafts.set(Object.fromEntries(open.map((q) => [q.id, { ...EMPTY_DRAFT }])));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  draft(id: string): AnswerDraft {
    return this.drafts()[id] ?? EMPTY_DRAFT;
  }

  choose(id: string, category: Category | null): void {
    this.patch(id, { category, skip: false });
  }

  toggleSkip(id: string): void {
    const skip = !this.draft(id).skip;
    this.patch(id, { skip, category: skip ? null : this.draft(id).category });
  }

  setHint(id: string, hint: string): void {
    this.patch(id, { hint });
  }

  setApplyToFuture(id: string, applyToFuture: boolean): void {
    this.patch(id, { applyToFuture });
  }

  submit(): void {
    if (!this.canSubmit()) return; // also blocks double clicks while a request is in flight
    this.submitting.set(true);
    const body: AnswersSubmission = {
      answers: this.questions().map((q): AnswerItem => {
        const d = this.draft(q.id);
        const hint = d.hint.trim() || null;
        return d.skip
          ? { questionId: q.id, skip: true, hint, applyToFuture: d.applyToFuture }
          : { questionId: q.id, category: d.category, skip: false, hint, applyToFuture: d.applyToFuture };
      }),
    };
    this.api.submitAnswers(this.statementId(), body, { context: handledErrors(409) }).subscribe({
      next: (statement) => {
        this.submitting.set(false);
        this.done.set(true);
        this.openQuestions.refresh();
        this.submitted.emit(statement);
      },
      error: (error: unknown) => {
        this.submitting.set(false);
        if (error instanceof HttpErrorResponse && error.status === 409) {
          this.conflictMessage.set(
            'These answers were already submitted (maybe from another tab). Loading the latest status…',
          );
          this.openQuestions.refresh();
          this.conflict.emit();
        }
        // Other errors were already shown by the error interceptor; the user can try again.
      },
    });
  }

  private patch(id: string, change: Partial<AnswerDraft>): void {
    if (this.submitting() || this.done()) return;
    this.drafts.update((all) => ({ ...all, [id]: { ...(all[id] ?? EMPTY_DRAFT), ...change } }));
  }
}
