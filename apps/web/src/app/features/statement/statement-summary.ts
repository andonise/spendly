import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import type { EChartsCoreOption } from 'echarts/core';
import { RouterLink } from '@angular/router';
import { NgxEchartsDirective } from 'ngx-echarts';
import { Account, Statement } from '../../core/api';
import { categoryLabel } from '../../shared/categories';
import { CategoryChip } from '../../shared/category-chip';
import { MoneyPipe, formatMoney } from '../../shared/money.pipe';

/** Single-hue bars: one series, so no categorical palette and no legend. */
const BAR = { light: '#3a6fd6', dark: '#8fb0f5' };
const INK = { light: { text: '#44474f', grid: '#e1e2ec' }, dark: { text: '#c4c6d0', grid: '#3a3d45' } };

function prefersDark(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/** Summary of a CATEGORIZED statement: totals + spending by category (chart and table). */
@Component({
  selector: 'app-statement-summary',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatButtonModule, MatCardModule, MatIconModule, NgxEchartsDirective, CategoryChip, MoneyPipe],
  template: `
    @if (statement().summary; as s) {
      <div class="tiles">
        <div class="tile">
          <div class="tile-label">Spent</div>
          <div class="tile-value spend">{{ abs(s.spendBase) | money }}</div>
        </div>
        <div class="tile">
          <div class="tile-label">Income</div>
          <div class="tile-value income">{{ abs(s.incomeBase) | money }}</div>
        </div>
        <div class="tile">
          <div class="tile-label">Transactions</div>
          <div class="tile-value">{{ s.transactionCount }}</div>
        </div>
      </div>

      <div class="actions-row">
        <p class="internal-note muted">
          <mat-icon aria-hidden="true">sync_alt</mat-icon>
          {{ s.internalTransferCount }} internal {{ s.internalTransferCount === 1 ? 'transfer' : 'transfers' }} excluded
        </p>
        <a mat-stroked-button class="view-transactions" routerLink="/transactions" [queryParams]="{ statementId: statement().id }">
          <mat-icon>list_alt</mat-icon> View transactions
        </a>
      </div>

      <mat-card appearance="outlined" class="section">
        <mat-card-header>
          <mat-card-title>Spending by category</mat-card-title>
          <mat-card-subtitle>{{ statement().periodStart }} – {{ statement().periodEnd }} · RON</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          @if (spending().length) {
            <div class="chart" echarts [options]="chartOptions()" [initOpts]="{ renderer: 'svg' }"
                 role="img" [attr.aria-label]="'Bar chart of spending by category; the table below lists the same values.'"></div>
          }
          <table class="breakdown">
            <thead><tr><th>Category</th><th class="num">Transactions</th><th class="num">Amount</th></tr></thead>
            <tbody>
              @for (row of s.byCategory; track row.category) {
                <tr>
                  <td><app-category-chip [category]="row.category" /></td>
                  <td class="num">{{ row.count }}</td>
                  <td class="num">{{ abs(row.amountBase) | money }}</td>
                </tr>
              }
            </tbody>
          </table>
        </mat-card-content>
      </mat-card>

      <mat-card appearance="outlined" class="section">
        <mat-card-content class="links">
          <mat-icon aria-hidden="true">insights</mat-icon>
          <span class="links-text">Report for {{ statement().periodStart }} – {{ statement().periodEnd }}
            {{ account() ? 'for ' + account()!.label : '' }}</span>
          <a mat-stroked-button class="view-report" routerLink="/dashboard" [queryParams]="reportLink()">Open report</a>
        </mat-card-content>
      </mat-card>
    } @else {
      <p class="muted">This statement is categorized, but no summary was returned.</p>
    }
  `,
  styles: `
    .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-bottom: 16px; }
    .tile { padding: 16px; border-radius: 12px; background: var(--mat-sys-surface-container); }
    .tile-label { font: var(--mat-sys-label-large); color: var(--mat-sys-on-surface-variant); }
    .tile-value { font: var(--mat-sys-headline-small); margin-top: 4px; }
    .actions-row { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: center; justify-content: space-between; margin-bottom: 16px; }
    .internal-note { display: inline-flex; align-items: center; gap: 6px; margin: 0; }
    .section { margin-bottom: 16px; }
    .chart { height: 320px; margin: 8px 0 16px; }
    .breakdown { width: 100%; border-collapse: collapse; }
    .breakdown th { text-align: left; font: var(--mat-sys-label-large); color: var(--mat-sys-on-surface-variant); padding: 8px 4px; }
    .breakdown td { padding: 6px 4px; border-top: 1px solid var(--mat-sys-outline-variant); }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .links { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; padding-top: 16px; }
    .links-text { flex: 1; min-width: 200px; }
  `,
})
export class StatementSummary {
  readonly statement = input.required<Statement>();
  readonly account = input<Account | null>(null);

  /** Spending categories (money out, transfers and income excluded), largest first. */
  protected readonly spending = computed(() =>
    (this.statement().summary?.byCategory ?? [])
      .filter((c) => c.category !== 'INTERNAL_TRANSFER' && c.category !== 'INCOME' && Number(c.amountBase) !== 0)
      .map((c) => ({ label: categoryLabel(c.category), value: Math.abs(Number(c.amountBase)), count: c.count }))
      .sort((a, b) => b.value - a.value),
  );

  protected readonly chartOptions = computed<EChartsCoreOption>(() => {
    const rows = [...this.spending()].reverse(); // ECharts draws category axes bottom-up
    const mode = prefersDark() ? 'dark' : 'light';
    return {
      animation: false,
      grid: { left: 8, right: 96, top: 8, bottom: 8, containLabel: true },
      tooltip: {
        trigger: 'item',
        formatter: (p: { name: string; value: number; dataIndex: number }) =>
          `${p.name}<br/><b>${formatMoney(p.value)}</b> · ${rows[p.dataIndex].count} txn`,
      },
      xAxis: {
        type: 'value',
        axisLabel: { show: false },
        splitLine: { lineStyle: { color: INK[mode].grid } },
      },
      yAxis: {
        type: 'category',
        data: rows.map((r) => r.label),
        axisTick: { show: false },
        axisLine: { show: false },
        axisLabel: { color: INK[mode].text },
      },
      series: [
        {
          type: 'bar',
          data: rows.map((r) => r.value),
          barMaxWidth: 18,
          itemStyle: { color: BAR[mode], borderRadius: [0, 4, 4, 0] },
          // Selective direct labels: only the top 3 bars carry a value.
          label: {
            show: true,
            position: 'right',
            color: INK[mode].text,
            formatter: (p: { value: number; dataIndex: number }) =>
              rows.length - p.dataIndex <= 3 ? formatMoney(p.value) : '',
          },
        },
      ],
    };
  });

  /** Dashboard for exactly the statement's dates and account (a CUSTOM range). */
  protected readonly reportLink = computed(() => {
    const s = this.statement();
    return { g: 'CUSTOM', from: s.periodStart, to: s.periodEnd, accounts: s.accountId };
  });

  protected abs(money: string): string {
    return money.startsWith('-') ? money.slice(1) : money;
  }
}
