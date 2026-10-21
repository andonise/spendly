import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CounterpartiesApi, TransactionsApi } from '../../core/api';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { ACCOUNT_IDS, STATEMENT_IDS, syntheticToken } from '../../core/mock/mock-data';
import { provideMockApiForTests } from '../../core/mock/testing';
import { OpenQuestionsStore } from '../../core/state/open-questions.store';
import { QuestionsPage } from '../questions/questions-page';
import { TransactionsPage } from './transactions-page';

@Component({ template: 'OTHER PAGE' })
class OtherPage {}

describe('TransactionsPage', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let api: TransactionsApi;
  let listSpy: ReturnType<typeof vi.spyOn>;

  const settle = async () => {
    for (let i = 0; i < 3; i++) {
      harness.fixture.detectChanges();
      await harness.fixture.whenStable();
    }
  };
  const el = () => harness.routeNativeElement as HTMLElement;
  const rows = () => [...el().querySelectorAll<HTMLElement>('tr[data-txn-id]')];
  /** Text with NBSPs (Intl currency output) folded to plain spaces. */
  const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ');
  const rowWith = (text: string) => rows().find((r) => r.textContent!.includes(text))!;
  const page = () => harness.routeDebugElement!.componentInstance as TransactionsPage;
  const params = () => router.parseUrl(router.url).queryParams;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'transactions', component: TransactionsPage },
            { path: 'questions', component: QuestionsPage },
            { path: '**', component: OtherPage },
          ],
          withComponentInputBinding(),
        ),
        ...provideMockApiForTests({ interceptors: [errorInterceptor] }),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    router = TestBed.inject(Router);
    api = TestBed.inject(TransactionsApi);
    listSpy = vi.spyOn(api, 'listTransactions');
    harness = await RouterTestingHarness.create();
  });

  const open = async (url: string) => {
    await harness.navigateByUrl(url);
    await settle();
  };

  it('reads filters from the URL and sends them to the server', async () => {
    await open(`/transactions?accountId=${ACCOUNT_IDS.card}&category=SHOPPING&transferStatus=NONE&from=2026-08-01&to=2026-08-31`);
    expect(listSpy).toHaveBeenLastCalledWith({
      accountId: ACCOUNT_IDS.card, category: 'SHOPPING', transferStatus: 'NONE', from: '2026-08-01', to: '2026-08-31',
      page: 0, size: 50, sort: 'bookingDate,desc',
    });
    expect(rows()).toHaveLength(1);
    expect(rows()[0].textContent).toContain('ALTEX IASI');
    expect(rows()[0].textContent).toContain('Generic Bank credit card');
    expect(text(rows()[0].querySelector('.amount-negative'))).toContain('-649,00 RON');
  });

  it('writes filter changes to the URL (keeping the others, back to page 0) and reloads', async () => {
    await open(`/transactions?statementId=${STATEMENT_IDS.current}&page=1&size=10`);
    page().setFilter('category', 'GROCERIES');
    await settle();
    expect(params()).toEqual({ statementId: STATEMENT_IDS.current, size: '10', category: 'GROCERIES' });
    expect(listSpy).toHaveBeenLastCalledWith(expect.objectContaining({ statementId: STATEMENT_IDS.current, category: 'GROCERIES', page: 0 }));
    expect(rows().map((r) => r.textContent)).toEqual([expect.stringContaining('LIDL'), expect.stringContaining('KAUFLAND')]);

    page().clearFilters();
    await settle();
    expect(params()).toEqual({ size: '10' });
  });

  it('pages and sorts on the server', async () => {
    await open('/transactions?size=10');
    expect(rows()).toHaveLength(10);
    expect(el().querySelector('.mat-mdc-paginator-range-label')?.textContent).toContain('32');

    page().onPage({ pageIndex: 3, pageSize: 10, length: 32 });
    await settle();
    expect(params()).toMatchObject({ page: '3', size: '10' });
    expect(listSpy).toHaveBeenLastCalledWith(expect.objectContaining({ page: 3, size: 10 }));
    expect(rows()).toHaveLength(2);

    page().onSort({ active: 'amount', direction: 'asc' });
    await settle();
    expect(params()).toMatchObject({ sort: 'amount,asc' });
    expect(params()['page']).toBeUndefined();
    expect(listSpy).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'amount,asc', page: 0 }));
    expect(text(rows()[0])).toContain('-2.200,00 RON');

    // Clicking the Date header sorts by date (desc first = the default, so it leaves the URL).
    el().querySelector<HTMLElement>('th.mat-column-bookingDate .mat-sort-header-container')!.click();
    await settle();
    expect(params()['sort']).toBeUndefined();
    expect(listSpy).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'bookingDate,desc' }));
  });

  it('shows badges, category provenance, and which rows can change category', async () => {
    await open('/transactions');
    expect(rowWith('TRANSFER CATRE REVOLUT').querySelector('.transfer-badge')?.textContent).toContain('Transfer');
    expect(rowWith('chirie august').querySelector('.transfer-badge')).toBeNull();
    expect(rowWith('chirie august').textContent).toContain('Landlord');
    const unconfirmed = rowWith('Top-Up from').querySelector<HTMLAnchorElement>('a.unconfirmed-badge')!;
    expect(unconfirmed.textContent).toContain('Unconfirmed transfer');
    expect(unconfirmed.getAttribute('href')).toMatch(/^\/questions\?focus=/);
    expect(rowWith('KAUFLAND 3412').querySelector('.edit-category')?.hasAttribute('disabled')).toBe(false);
    expect(rowWith('TRANSFER CATRE REVOLUT').querySelector('.edit-category')?.hasAttribute('disabled')).toBe(true);
    expect(rowWith('Top-Up from').querySelector('.edit-category')?.hasAttribute('disabled')).toBe(true);
    expect(rowWith('KAUFLAND 3412').querySelector('.label-counterparty')).toBeNull(); // no counterparty
  });

  it('the Transfer badge navigates to the paired transaction and highlights it', async () => {
    await open('/transactions?category=INTERNAL_TRANSFER');
    const pair = (await new Promise<string>((resolve) =>
      api.listTransactions({ statementId: STATEMENT_IDS.revolut, transferStatus: 'INTERNAL' }).subscribe((p) => resolve(p.items[0].id)),
    ));
    rowWith('TRANSFER CATRE REVOLUT').querySelector<HTMLButtonElement>('button.transfer-badge')!.click();
    await settle();
    expect(params()).toEqual({ accountId: ACCOUNT_IDS.revolut, from: '2026-08-11', to: '2026-08-11', highlight: pair });
    const highlighted = el().querySelector<HTMLElement>('tr.highlighted')!;
    expect(highlighted.dataset['txnId']).toBe(pair);
    expect(highlighted.textContent).toContain('Top-Up by *3333');
    expect(el().textContent).toContain('Showing the other side of the transfer');
  });

  it('"Label counterparty" opens the dialog and PUTs the label, then refreshes', async () => {
    const put = vi.spyOn(TestBed.inject(CounterpartiesApi), 'updateCounterparty');
    await open('/transactions');
    rowWith('Top-Up from').querySelector<HTMLButtonElement>('button.label-counterparty')!.click();
    await settle();

    const dialog = document.querySelector<HTMLElement>('app-counterparty-dialog')!;
    expect(dialog.textContent).toContain('Label counterparty');
    expect(dialog.textContent).not.toContain(syntheticToken('unknownIban')); // only a short prefix
    const input = dialog.querySelector<HTMLInputElement>('input[name="label"]')!;
    input.value = 'Savings at Test Bank';
    input.dispatchEvent(new Event('input'));
    dialog.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    await settle();
    dialog.querySelector<HTMLButtonElement>('button.save')!.click();
    await settle();
    // afterClosed waits for the close animation's fallback timer in jsdom.
    await new Promise((resolve) => setTimeout(resolve, 400));
    await settle();

    expect(put).toHaveBeenCalledWith(syntheticToken('unknownIban'), { label: 'Savings at Test Bank', isOwnAccount: true });
    const row = rowWith('Top-Up from');
    expect(row.textContent).toContain('Savings at Test Bank');
    expect(row.querySelector('.transfer-badge')?.textContent).toContain('Transfer'); // now INTERNAL
    expect(row.querySelector('.unconfirmed-badge')).toBeNull();
  });

  it('"Change category" PATCHes the transaction and updates the row', async () => {
    const patch = vi.spyOn(api, 'updateTransactionCategory');
    await open('/transactions');
    const id = rowWith('KAUFLAND 3412').dataset['txnId']!;
    rowWith('KAUFLAND 3412').querySelector<HTMLButtonElement>('button.edit-category')!.click();
    await settle();

    const dialog = document.querySelector<HTMLElement>('app-category-dialog')!;
    expect(dialog.textContent).toContain('KAUFLAND 3412');
    expect(dialog.querySelector<HTMLButtonElement>('button.save')!.disabled).toBe(true); // unchanged category
    dialog.querySelector<HTMLElement>('.mat-mdc-select-trigger')!.click();
    await settle();
    const options = [...document.querySelectorAll<HTMLElement>('mat-option')];
    expect(options.some((o) => o.textContent!.includes('Internal transfer'))).toBe(false);
    options.find((o) => o.textContent!.includes('Restaurants'))!.click();
    dialog.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    await settle();
    dialog.querySelector<HTMLButtonElement>('button.save')!.click();
    await settle();
    await new Promise((resolve) => setTimeout(resolve, 400));
    await settle();

    expect(patch).toHaveBeenCalledWith(id, { category: 'RESTAURANTS', applyToFuture: true });
    expect(rowWith('KAUFLAND 3412').textContent).toContain('Restaurants');
  });

  it('end to end: answering a TRANSFER question "Yes" on /questions makes it INTERNAL on /transactions', async () => {
    await open('/questions');
    TestBed.inject(OpenQuestionsStore).refresh();
    const card = el().querySelector<HTMLElement>('mat-card.transfer')!;
    expect(card.textContent).toContain('Top-Up from RO**…7788');
    card.querySelector<HTMLButtonElement>('button.yes')!.click();
    await settle();
    expect(TestBed.inject(OpenQuestionsStore).count()).toBe(2);

    await open('/transactions?transferStatus=INTERNAL');
    const row = rowWith('Top-Up from RO**…7788');
    expect(row).toBeDefined();
    expect(row.querySelector('.transfer-badge')?.textContent).toContain('Transfer');
    const txn = await new Promise((resolve) =>
      api.getTransaction(row.dataset['txnId']!).subscribe(resolve),
    );
    expect(txn).toMatchObject({ transferStatus: 'INTERNAL', category: 'INTERNAL_TRANSFER', categorySource: 'USER' });
  });

  it('shows an empty state when nothing matches', async () => {
    await open('/transactions?from=2030-01-01');
    expect(rows()).toHaveLength(0);
    expect(el().textContent).toContain('No transactions match these filters');
  });
});
