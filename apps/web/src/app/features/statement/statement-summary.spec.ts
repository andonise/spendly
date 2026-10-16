import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Statement } from '../../core/api';
import { StatementSummary } from './statement-summary';

describe('StatementSummary', () => {
  it('shows absolute totals, "N internal transfers excluded" and a link to the statement transactions', async () => {
    TestBed.configureTestingModule({ imports: [StatementSummary], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(StatementSummary);
    const statement: Statement = {
      id: 's1', accountId: 'a1', fileType: 'PDF', status: 'CATEGORIZED', periodStart: '2026-08-01', periodEnd: '2026-08-31',
      createdAt: '2026-09-02T18:30:00Z', updatedAt: '2026-09-02T18:31:00Z',
      // Income only, so the (lazily loaded) chart is not rendered in jsdom.
      summary: {
        transactionCount: 5, spendBase: '951.00', incomeBase: '150.00', internalTransferCount: 2,
        byCategory: [{ category: 'INCOME', amountBase: '150.00', count: 1 }],
      },
    };
    fixture.componentRef.setInput('statement', statement);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const text = el.textContent!.replace(/\s+/g, ' ');

    expect(el.querySelector('.tile-value.spend')?.textContent?.replace(/\s+/g, ' ')).toContain('951,00 RON');
    expect(el.querySelector('.tile-value.income')?.textContent?.replace(/\s+/g, ' ')).toContain('150,00 RON');
    expect(text).toContain('2 internal transfers excluded');
    expect(el.querySelector('.amount-negative')).toBeNull();
    expect(el.querySelector('a.view-transactions')?.getAttribute('href')).toBe('/transactions?statementId=s1');

    fixture.componentRef.setInput('statement', { ...statement, summary: { ...statement.summary!, internalTransferCount: 1 } });
    fixture.detectChanges();
    expect(el.textContent!.replace(/\s+/g, ' ')).toContain('1 internal transfer excluded');
  });
});
