import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { firstValueFrom, map } from 'rxjs';
import { QuestionsApi, TransactionsApi } from '../../core/api';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { STATEMENT_IDS } from '../../core/mock/mock-data';
import { provideMockApiForTests } from '../../core/mock/testing';
import { OpenQuestionsStore } from '../../core/state/open-questions.store';
import { QuestionsPage } from './questions-page';

describe('QuestionsPage', () => {
  let harness: RouterTestingHarness;
  let el: HTMLElement;
  let api: QuestionsApi;
  let snackOpen: ReturnType<typeof vi.fn>;

  const settle = async () => {
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
    harness.fixture.detectChanges();
  };
  const transferCards = () => [...el.querySelectorAll<HTMLElement>('mat-card.transfer')];
  const button = (root: HTMLElement, selector: string) => root.querySelector<HTMLButtonElement>(selector)!;
  const store = () => TestBed.inject(OpenQuestionsStore);

  async function setup(extraProviders: unknown[] = []) {
    snackOpen = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'questions', component: QuestionsPage }], withComponentInputBinding()),
        ...provideMockApiForTests({ interceptors: [errorInterceptor] }),
        { provide: MatSnackBar, useValue: { open: snackOpen } },
        ...(extraProviders as never[]),
      ],
    });
    api = TestBed.inject(QuestionsApi);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/questions');
    el = harness.routeNativeElement!;
    await settle();
    store().refresh();
    await settle();
  }

  it('groups open questions: TRANSFER cards here, statement questions linked to their statement page', async () => {
    await setup();
    expect(transferCards()).toHaveLength(1);
    const card = transferCards()[0].textContent!.replace(/\s+/g, ' ');
    expect(card).toContain('Top-Up from RO**…7788');
    expect(card).toContain('Revolut · 2026-08-24');
    expect(card).toContain('300,00 RON');
    expect(card).toContain('Is this a transfer to one of your accounts?');
    expect(card).toContain("Yes, it's my transfer");
    // The transaction has a counterparty → the own-account checkbox is offered.
    expect(card).toContain('Always treat this counterparty as my own account');

    const group = el.querySelector<HTMLElement>(`[data-statement-id="${STATEMENT_IDS.revolut}"]`)!;
    expect(group.textContent).toContain('2 questions');
    expect(group.textContent).toContain('Bolt');
    expect(group.querySelector('a.answer-link')!.getAttribute('href')).toBe(`/statements/${STATEMENT_IDS.revolut}`);
  });

  it('Yes (+ always own account) sends INTERNAL_TRANSFER with applyToFuture, once, and refreshes the badge', async () => {
    await setup();
    const spy = vi.spyOn(api, 'answerQuestion');
    expect(store().count()).toBe(3);
    const card = transferCards()[0];
    const id = card.dataset['questionId']!;

    card.querySelector<HTMLInputElement>('.apply-future input')!.click();
    await settle();
    button(card, 'button.yes').click();
    button(card, 'button.yes').click(); // double click → still one request
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls[0][0]).toBe(id);
    expect(spy.mock.calls[0][1]).toEqual({ category: 'INTERNAL_TRANSFER', applyToFuture: true });
    expect(transferCards()).toHaveLength(0);
    expect(el.textContent).toContain('marked as an internal transfer');
    expect(store().count()).toBe(2);
  });

  it('No → category picker → sends that category (EXTERNAL); Skip sends skip: true', async () => {
    await setup();
    const spy = vi.spyOn(api, 'answerQuestion');
    const card = transferCards()[0];
    expect(card.querySelector('.picker')).toBeNull();
    button(card, 'button.no').click();
    await settle();
    const suggestions = [...card.querySelectorAll<HTMLButtonElement>('.picker button.suggestion')].map((b) => b.textContent!.trim());
    expect(suggestions).toEqual(['Income', 'Other']); // INTERNAL_TRANSFER is not offered after "No"
    [...card.querySelectorAll<HTMLButtonElement>('.picker button.suggestion')][0].click();
    await settle();
    expect(spy.mock.calls[0][1]).toEqual({ category: 'INCOME' });

    // Skip, on a fresh page.
    TestBed.resetTestingModule();
    await setup();
    const skipSpy = vi.spyOn(api, 'answerQuestion');
    button(transferCards()[0], 'button.skip').click();
    await settle();
    expect(skipSpy.mock.calls[0][1]).toEqual({ skip: true });
  });

  it('handles 409 (already answered elsewhere) gracefully: message, refreshed list, no error snackbar', async () => {
    await setup();
    const card = transferCards()[0];
    await firstValueFrom(api.answerQuestion(card.dataset['questionId']!, { skip: true })); // another tab
    button(card, 'button.yes').click();
    await settle();
    expect(el.textContent).toContain('already answered');
    expect(transferCards()).toHaveLength(0);
    expect(snackOpen).not.toHaveBeenCalled();
    expect(store().count()).toBe(2);
  });

  it('hides the own-account checkbox when the transaction has no counterparty', async () => {
    await setup([
      {
        provide: TransactionsApi,
        useFactory: () => {
          const real = new TransactionsApi();
          return { getTransaction: (id: string) => real.getTransaction(id).pipe(map((t) => ({ ...t, counterparty: null }))) };
        },
      },
    ]);
    expect(transferCards()).toHaveLength(1);
    expect(transferCards()[0].textContent).not.toContain('Always treat this counterparty');
  });
});
