import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideEchartsCore } from 'ngx-echarts';
import { of, throwError } from 'rxjs';
import { Report, ReportsApi } from '../../core/api';
import { errorInterceptor } from '../../core/http/error.interceptor';
import { ACCOUNT_IDS } from '../../core/mock/mock-data';
import { provideMockApiForTests } from '../../core/mock/testing';
import { DashboardPage } from './dashboard-page';
import { periodFor, periodLabel, rangeLabel, shift } from './period';
import { compact } from './dashboard-charts';

describe('DashboardPage', () => {
  let harness: RouterTestingHarness;
  let router: Router;
  let api: ReportsApi;

  const settle = async () => {
    for (let i = 0; i < 4; i++) {
      harness.fixture.detectChanges();
      await harness.fixture.whenStable();
    }
  };
  const el = () => harness.routeNativeElement as HTMLElement;
  const text = (selector: string) => (el().querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const params = () => router.parseUrl(router.url).queryParams;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'dashboard', component: DashboardPage }], withComponentInputBinding()),
        ...provideMockApiForTests({ interceptors: [errorInterceptor], pollIntervalMs: 5 }),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        provideEchartsCore({ echarts: () => import('../../core/charts/echarts').then((m) => m.echarts) }),
      ],
    });
    router = TestBed.inject(Router);
    api = TestBed.inject(ReportsApi);
    harness = await RouterTestingHarness.create();
  });

  const open = async (url: string) => {
    await harness.navigateByUrl(url);
    await settle();
  };

  it('defaults to the month of the latest categorized statement and shows its totals', async () => {
    const get = vi.spyOn(api, 'getReport');
    await open('/dashboard');
    expect(text('.period-title')).toBe('August 2026');
    expect(get).toHaveBeenLastCalledWith({ granularity: 'MONTH', from: '2026-08-01', to: undefined, accountIds: [] });
    const report = await new Promise<Report>((resolve) => api.getReport({ granularity: 'MONTH', from: '2026-08-01' }).subscribe(resolve));
    expect(Number(report.summary!.totals.spendBase)).toBeGreaterThan(0);
    expect(text('.tile-value.spend').replace(/\D/g, '')).toBe(report.summary!.totals.spendBase.replace(/\D/g, ''));
    expect(el().querySelectorAll('.breakdown tbody tr').length).toBeGreaterThan(0);
    expect(el().querySelector('.history')).not.toBeNull(); // 6-month history for MONTH
    expect(el().querySelector('.savings-rate, .tile-value')).not.toBeNull();
  });

  it('moves between periods and granularities through the URL', async () => {
    await open('/dashboard?from=2026-08-01');
    el().querySelector<HTMLButtonElement>('button.next')!.click();
    await settle();
    expect(params()['from']).toBe('2026-09-01');
    expect(text('.period-title')).toBe('September 2026');

    const toggles = [...el().querySelectorAll<HTMLButtonElement>('mat-button-toggle button')];
    toggles.find((b) => b.textContent!.includes('Week'))!.click();
    await settle();
    expect(params()).toMatchObject({ g: 'WEEK', from: '2026-08-31' }); // the Monday of 1 Sep
    el().querySelector<HTMLButtonElement>('button.prev')!.click();
    await settle();
    expect(params()['from']).toBe('2026-08-24');

    toggles.find((b) => b.textContent!.includes('Custom'))!.click();
    await settle();
    expect(params()).toMatchObject({ g: 'CUSTOM', from: '2026-08-24', to: '2026-08-30' });
    expect(el().querySelector('input.f-to')).not.toBeNull();
  });

  it('filters by account', async () => {
    const get = vi.spyOn(api, 'getReport');
    await open(`/dashboard?from=2026-08-01&accounts=${ACCOUNT_IDS.revolut}`);
    expect(get).toHaveBeenLastCalledWith(expect.objectContaining({ accountIds: [ACCOUNT_IDS.revolut] }));
    expect(el().querySelector('.accounts-card')).toBeNull(); // one account: no per-account table
  });

  it('shows the coverage banner and an empty state', async () => {
    await open('/dashboard?from=2030-01-01');
    expect(el().textContent).toContain('No statements for this period');

    await open('/dashboard?g=CUSTOM&from=2026-07-25&to=2026-08-05');
    expect(el().querySelector('.banner.coverage')?.textContent).toContain('Partial period');
    expect(text('.gaps')).toContain('Generic Bank current: 25 – 31 Jul 2026');
  });

  it('shows the old numbers with "Updating…" and polls a stale report until it is rebuilt', async () => {
    const fresh = await new Promise<Report>((resolve) => api.getReport({ granularity: 'MONTH', from: '2026-08-01' }).subscribe(resolve));
    vi.spyOn(api, 'getReport').mockReturnValue(of({ ...fresh, stale: true, status: 'STALE' }));
    const byId = vi.spyOn(api, 'getReportById').mockReturnValueOnce(of({ ...fresh, stale: true, status: 'STALE' })).mockReturnValue(of(fresh));
    await open('/dashboard?from=2026-08-01');
    expect(el().querySelector('.updating')?.textContent).toContain('Updating');
    for (let i = 0; i < 100 && el().querySelector('.updating'); i++) {
      await new Promise((resolve) => setTimeout(resolve, 20));
      await settle();
    }
    expect(byId).toHaveBeenCalledTimes(2);
    expect(el().querySelector('.updating')).toBeNull();
  });

  it('shows insights, the cold-start note and warnings with an icon and a label', async () => {
    const fresh = await new Promise<Report>((resolve) => api.getReport({ granularity: 'MONTH', from: '2026-08-01' }).subscribe(resolve));
    vi.spyOn(api, 'getReport').mockReturnValue(of({
      ...fresh,
      insights: [
        { type: 'CARD_INTEREST' as never, severity: 'WARNING', message: 'Unknown type still renders', data: {} },
        { type: 'CATEGORY_SPIKE', severity: 'WARNING', message: 'Restaurants spending is 45% above your usual', data: {} },
      ],
    }));
    await open('/dashboard?from=2026-08-01');
    const insights = [...el().querySelectorAll<HTMLElement>('article.insight')];
    expect(insights).toHaveLength(2);
    expect(insights[1].textContent).toContain('Worth a look');
    expect(insights[1].querySelector('.insight-icon')!.textContent).toBe('trending_up');
    expect(insights[0].querySelector('.insight-icon')!.textContent).toBe('lightbulb');
    expect(el().querySelector('.history-note')?.textContent).toContain('Not enough history yet');
  });

  it('offers a retry when the report fails to load', async () => {
    const get = vi.spyOn(api, 'getReport').mockReturnValueOnce(throwError(() => new Error('down')));
    await open('/dashboard?from=2026-08-01');
    expect(el().textContent).toContain('The report could not be loaded');
    get.mockRestore();
    el().querySelector<HTMLButtonElement>('app-empty-state button')!.click();
    await settle();
    expect(el().querySelector('.tiles')).not.toBeNull();
  });
});

describe('dashboard periods', () => {
  it('computes and shifts periods', () => {
    expect(periodFor('WEEK', '2026-08-13')).toEqual({ from: '2026-08-10', to: '2026-08-16' });
    expect(periodFor('MONTH', '2026-02-13')).toEqual({ from: '2026-02-01', to: '2026-02-28' });
    expect(periodFor('CUSTOM', '2026-08-01', '2026-08-10')).toEqual({ from: '2026-08-01', to: '2026-08-10' });
    expect(periodFor('CUSTOM', '2026-08-01')).toEqual({ from: '2026-08-01', to: '2026-08-30' });
    expect(shift('DAY', { from: '2026-03-01', to: '2026-03-01' }, -1)).toEqual({ from: '2026-02-28', to: '2026-02-28' });
    expect(shift('MONTH', { from: '2026-12-01', to: '2026-12-31' }, 1)).toEqual({ from: '2027-01-01', to: '2027-01-31' });
    expect(shift('CUSTOM', { from: '2026-08-01', to: '2026-08-10' }, 1)).toEqual({ from: '2026-08-11', to: '2026-08-20' });
  });

  it('labels periods', () => {
    expect(periodLabel('MONTH', { from: '2026-08-01', to: '2026-08-31' })).toBe('August 2026');
    expect(periodLabel('DAY', { from: '2026-08-12', to: '2026-08-12' })).toBe('12 Aug 2026');
    expect(rangeLabel({ from: '2026-08-10', to: '2026-08-16' })).toBe('10 – 16 Aug 2026');
    expect(rangeLabel({ from: '2026-07-27', to: '2026-08-02' })).toBe('27 Jul – 2 Aug 2026');
    expect(rangeLabel({ from: '2025-12-29', to: '2026-01-04' })).toBe('29 Dec 2025 – 4 Jan 2026');
    expect(compact(1250)).toBe('1.3k');
    expect(compact(12_500)).toBe('13k');
    expect(compact(950)).toBe('950');
  });
});
