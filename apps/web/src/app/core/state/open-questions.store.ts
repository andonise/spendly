import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Subscription, interval } from 'rxjs';
import { QuestionsApi, handledErrors } from '../api';

/** Feeds the "N questions need your input" badge from GET /api/questions?status=OPEN. */
@Injectable({ providedIn: 'root' })
export class OpenQuestionsStore {
  private readonly api = inject(QuestionsApi);
  private pending?: Subscription;

  readonly count = signal(0);

  constructor() {
    const timer = interval(30_000).subscribe(() => this.refresh());
    inject(DestroyRef).onDestroy(() => timer.unsubscribe());
  }

  refresh(): void {
    this.pending?.unsubscribe();
    // Background refresh: stay silent when the backend is down.
    this.pending = this.api
      .listOpenQuestions({ status: 'OPEN' }, { context: handledErrors(0, 404, 500, 502, 503, 504) })
      .subscribe({
        next: (questions) => this.count.set(questions.length),
        error: () => undefined,
      });
  }
}
