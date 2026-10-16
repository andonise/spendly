import { Component, input, output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import { Account, AccountsApi, Statement, StatementStatus, StatementStatusEvent, StatementsApi } from '../../core/api';
import { StatementEventsService } from '../../core/sse/statement-events.service';
import { OpenQuestionsStore } from '../../core/state/open-questions.store';
import { QuestionsForm } from '../questions/questions-form';
import { StatementPage } from './statement-page';
import { StatementSummary } from './statement-summary';

@Component({ selector: 'app-questions-form', template: 'QUESTIONS FORM' })
class FakeQuestionsForm {
  readonly statementId = input.required<string>();
  readonly submitted = output<Statement>();
  readonly conflict = output<void>();
}

@Component({ selector: 'app-statement-summary', template: 'SUMMARY' })
class FakeSummary {
  readonly statement = input.required<Statement>();
  readonly account = input<Account | null>(null);
}

const ACCOUNT: Account = {
  id: 'a1', label: 'Generic Bank current', bank: 'Generic Bank', type: 'CURRENT', currency: 'RON',
  ibanMasked: 'RO**…3333', activeFrom: '2026-01-01', createdAt: '2026-09-01T08:00:00Z',
};

describe('StatementPage', () => {
  let fixture: ComponentFixture<StatementPage>;
  let el: HTMLElement;
  let current: Statement;
  let events$: Subject<StatementStatusEvent>;
  let watchCount: number;
  const retryStatement = vi.fn();

  const statementWith = (status: StatementStatus, extra: Partial<Statement> = {}): Statement => ({
    id: 's1', accountId: 'a1', fileType: 'CSV', status, lastCompletedStep: null,
    createdAt: '2026-10-15T10:00:00Z', updatedAt: '2026-10-15T10:00:00Z', ...extra,
  });

  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
  };

  /** Simulates a status change: the server state moves, then an event arrives. */
  const push = async (status: StatementStatus, extra: Partial<Statement> = {}) => {
    current = statementWith(status, extra);
    events$.next({ statementId: 's1', status, occurredAt: current.updatedAt });
    await settle();
  };

  async function create(initial: Statement) {
    current = initial;
    events$ = new Subject();
    watchCount = 0;
    retryStatement.mockReset();
    TestBed.configureTestingModule({
      imports: [StatementPage],
      providers: [
        provideRouter([]),
        { provide: StatementsApi, useValue: { getStatement: () => of(current), retryStatement } },
        { provide: AccountsApi, useValue: { getAccount: () => of(ACCOUNT) } },
        { provide: StatementEventsService, useValue: { watch: () => (watchCount++, events$.asObservable()) } },
        { provide: OpenQuestionsStore, useValue: { refresh: vi.fn() } },
      ],
    }).overrideComponent(StatementPage, {
      remove: { imports: [QuestionsForm, StatementSummary] },
      add: { imports: [FakeQuestionsForm, FakeSummary] },
    });
    fixture = TestBed.createComponent(StatementPage);
    fixture.componentRef.setInput('id', 's1');
    el = fixture.nativeElement;
    await settle();
  }

  it('switches views as the status changes: progress → questions → summary', async () => {
    await create(statementWith('UPLOADED'));
    expect(el.querySelector('app-progress')?.textContent).toContain('Reading your statement');
    expect(el.textContent).toContain('Generic Bank current');

    await push('PARSED', { progress: { transactionsImported: 18 } });
    expect(el.querySelector('app-progress')?.textContent).toContain('Categorizing expenses');
    expect(el.textContent).toContain('18 transactions imported');

    await push('CATEGORIZING', { progress: { transactionsImported: 18, transactionsCategorized: 15 } });
    expect(el.textContent).toContain('15 of 18 transactions categorized');

    await push('NEEDS_INPUT');
    expect(el.querySelector('app-progress')).toBeNull();
    expect(el.querySelector('app-questions-form')).not.toBeNull();

    await push('CATEGORIZED');
    expect(el.querySelector('app-questions-form')).toBeNull();
    expect(el.querySelector('app-statement-summary')?.textContent).toContain('SUMMARY');
    expect(fixture.componentInstance.status()).toBe('CATEGORIZED');
  });

  it('restores the same view on reload (status comes from GET on load)', async () => {
    await create(statementWith('NEEDS_INPUT'));
    expect(el.querySelector('app-questions-form')).not.toBeNull();
  });

  it('shows FAILED with code and message, and Retry calls POST /retry and follows again', async () => {
    await create(
      statementWith('FAILED', {
        lastCompletedStep: 'UPLOAD',
        error: { code: 'BALANCE_MISMATCH', message: 'Balances do not add up.', retryable: true },
      }),
    );
    expect(el.textContent).toContain('Processing failed');
    expect(el.textContent).toContain('BALANCE_MISMATCH');
    expect(el.textContent).toContain('Balances do not add up.');

    const watchesBefore = watchCount;
    retryStatement.mockReturnValue(of(statementWith('UPLOADED')));
    el.querySelector<HTMLButtonElement>('button.retry')!.click();
    await settle();

    expect(retryStatement).toHaveBeenCalledWith('s1');
    expect(el.querySelector('app-progress')?.textContent).toContain('Reading your statement');
    expect(watchCount).toBe(watchesBefore + 1);
  });

  it('after answers are submitted, shows progress immediately', async () => {
    await create(statementWith('NEEDS_INPUT'));
    fixture.componentInstance.onAnswered(statementWith('CATEGORIZING'));
    await settle();
    expect(el.querySelector('app-progress')?.textContent).toContain('Categorizing expenses');
  });
});
