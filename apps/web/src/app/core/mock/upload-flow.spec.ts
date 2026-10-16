import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { filter, firstValueFrom, last, toArray } from 'rxjs';
import { QuestionsApi, StatementsApi } from '../api';
import { errorInterceptor } from '../http/error.interceptor';
import { StatementEventsService } from '../sse/statement-events.service';
import { ACCOUNT_IDS } from './mock-data';
import { provideMockApiForTests, sampleFile } from './testing';

/**
 * End-to-end flow through the real services, interceptors and mock API with real timers:
 * upload → progress (via the status service, polling) → questions → answers → summary.
 */
describe('Upload flow against the mock API', () => {
  it('goes from upload to a categorized summary', async () => {
    const snackOpen = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideMockApiForTests({ stepMs: 30, pollIntervalMs: 5, interceptors: [errorInterceptor] }),
        { provide: MatSnackBar, useValue: { open: snackOpen } },
      ],
    });
    const statements = TestBed.inject(StatementsApi);
    const questions = TestBed.inject(QuestionsApi);
    const events = TestBed.inject(StatementEventsService);

    const { statementId, duplicate } = await firstValueFrom(
      statements.uploadStatement(ACCOUNT_IDS.current, sampleFile('flow-2026-08.csv')),
    );
    expect(duplicate).toBe(false);

    // Follow the status until the pipeline asks for input.
    const seen: string[] = [];
    await firstValueFrom(
      events.watch(statementId).pipe(
        filter((e) => {
          seen.push(e.status);
          return e.status === 'NEEDS_INPUT';
        }),
      ),
    );
    expect(seen[0]).toBe('UPLOADED');
    expect(seen).toContain('NEEDS_INPUT');
    expect(events.transport()).toBe('polling');

    const open = await firstValueFrom(questions.getStatementQuestions(statementId));
    expect(open).toHaveLength(3);
    const accepted = await firstValueFrom(
      questions.submitAnswers(statementId, {
        answers: open.map((q, i) => (i === 0 ? { questionId: q.id, skip: true } : { questionId: q.id, category: q.suggestions[0] })),
      }),
    );
    expect(accepted.status).toBe('CATEGORIZING');

    // The stream completes on the terminal status.
    const rest = await firstValueFrom(events.watch(statementId).pipe(toArray()));
    expect(rest.at(-1)?.status).toBe('CATEGORIZED');
    const final = await firstValueFrom(events.watch(statementId).pipe(last()));
    expect(final.status).toBe('CATEGORIZED');

    const summary = (await firstValueFrom(statements.getStatement(statementId))).summary!;
    expect(summary.transactionCount).toBe(18);
    expect(summary.byCategory.find((c) => c.category === 'OTHER')?.amountBase).toBe('2200.00');
    expect(snackOpen).not.toHaveBeenCalled();
  });
});
