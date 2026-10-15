import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { AnswersSubmission, QuestionsApi, Statement } from '../../core/api';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { provideMockApiForTests } from '../../core/mock/testing';
import { QuestionsForm } from './questions-form';

/** Seeded Revolut statement: NEEDS_INPUT with 2 category questions (Bolt, transfer to a person). */
const REVOLUT_STATEMENT = '5a7c2d10-8e3b-4f6a-b1c2-0000000000b2';

describe('QuestionsForm', () => {
  let fixture: ComponentFixture<QuestionsForm>;
  let el: HTMLElement;
  let api: QuestionsApi;
  let snackOpen: ReturnType<typeof vi.fn>;

  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
  };
  const cards = () => [...el.querySelectorAll<HTMLElement>('mat-card[data-question-id]')];
  const submitButton = () => el.querySelector<HTMLButtonElement>('button.submit')!;
  const buttonIn = (card: HTMLElement, text: string) =>
    [...card.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.includes(text))!;

  beforeEach(async () => {
    snackOpen = vi.fn();
    TestBed.configureTestingModule({
      imports: [QuestionsForm],
      providers: [
        ...provideMockApiForTests({ interceptors: [errorInterceptor] }),
        { provide: MatSnackBar, useValue: { open: snackOpen } },
      ],
    });
    api = TestBed.inject(QuestionsApi);
    fixture = TestBed.createComponent(QuestionsForm);
    fixture.componentRef.setInput('statementId', REVOLUT_STATEMENT);
    el = fixture.nativeElement;
    await settle();
  });

  it('renders one card per open question with transaction, amount and account', () => {
    expect(cards()).toHaveLength(2);
    const text = cards()[0].textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('Bolt');
    expect(text).toContain('-18,40 RON');
    expect(text).toContain('2026-08-17');
    expect(text).toContain('Revolut');
    expect(text).toContain('Was this Bolt payment a ride or a food delivery?');
    expect(text).toContain('Transport'); // suggestion button
    expect(text).toContain('Apply to future transactions');
  });

  it('keeps Submit disabled until every card is answered or skipped, and sends skip: true', async () => {
    const submitted: Statement[] = [];
    fixture.componentInstance.submitted.subscribe((s) => submitted.push(s));
    const spy = vi.spyOn(api, 'submitAnswers');
    const [bolt, person] = cards();

    expect(submitButton().disabled).toBe(true);
    buttonIn(bolt, 'Transport').click();
    await settle();
    expect(submitButton().disabled).toBe(true);

    buttonIn(person, "Skip / I don't know").click();
    await settle();
    expect(submitButton().disabled).toBe(false);

    // Optional fields on the first card.
    fixture.componentInstance.setHint(bolt.dataset['questionId']!, '  ride home  ');
    fixture.componentInstance.setApplyToFuture(bolt.dataset['questionId']!, true);
    await settle();

    // Double click → exactly one request.
    submitButton().click();
    submitButton().click();
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    const body = spy.mock.calls[0][1] as AnswersSubmission;
    expect(body.answers).toEqual([
      { questionId: bolt.dataset['questionId'], category: 'TRANSPORT', skip: false, hint: 'ride home', applyToFuture: true },
      { questionId: person.dataset['questionId'], skip: true, hint: null, applyToFuture: false },
    ]);
    expect(submitted).toHaveLength(1);
    expect(submitted[0].status).toBe('CATEGORIZING');
    expect(submitButton().disabled).toBe(true);
  });

  it('un-skips when a category is picked afterwards', async () => {
    const [bolt] = cards();
    buttonIn(bolt, "Skip / I don't know").click();
    await settle();
    const id = bolt.dataset['questionId']!;
    expect(fixture.componentInstance.draft(id)).toMatchObject({ skip: true, category: null });
    buttonIn(bolt, 'Restaurants').click();
    await settle();
    expect(fixture.componentInstance.draft(id)).toMatchObject({ skip: false, category: 'RESTAURANTS' });
  });

  it('handles 409 (already submitted elsewhere) with a friendly message instead of an error snackbar', async () => {
    const conflicts: unknown[] = [];
    fixture.componentInstance.conflict.subscribe(() => conflicts.push(true));
    const [bolt, person] = cards();
    buttonIn(bolt, 'Transport').click();
    buttonIn(person, "Skip / I don't know").click();
    await settle();

    // Another tab submits first.
    await firstValueFrom(
      api.submitAnswers(REVOLUT_STATEMENT, {
        answers: cards().map((c) => ({ questionId: c.dataset['questionId']!, skip: true })),
      }),
    );

    submitButton().click();
    await settle();

    expect(conflicts).toHaveLength(1);
    expect(el.textContent).toContain('These answers were already submitted');
    expect(snackOpen).not.toHaveBeenCalled();
    expect(submitButton().disabled).toBe(true);
  });
});
