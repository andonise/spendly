import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Account, AccountsApi, STATEMENT_STATUSES, Statement, StatementStatus, StatementsApi } from '../../core/api';
import { EmptyState } from '../../shared/empty-state';
import { StatusChip } from '../../shared/status-chip';

/** All statements, newest first, optionally filtered by `?status=`. */
@Component({
  selector: 'app-statements-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatProgressBarModule,
    MatTableModule, EmptyState, StatusChip,
  ],
  template: `
    <header class="header">
      <div>
        <h1 class="page-title">Statements</h1>
        <p class="page-subtitle">Every uploaded statement and where it is in the pipeline.</p>
      </div>
      <a mat-flat-button routerLink="/upload"><mat-icon>upload_file</mat-icon> Upload</a>
    </header>

    <mat-button-toggle-group
      [value]="filter() ?? 'ALL'"
      (change)="setFilter($event.value)"
      aria-label="Filter by status"
      hideSingleSelectionIndicator
    >
      <mat-button-toggle value="ALL">All</mat-button-toggle>
      <mat-button-toggle value="NEEDS_INPUT">Needs input</mat-button-toggle>
      <mat-button-toggle value="CATEGORIZED">Done</mat-button-toggle>
      <mat-button-toggle value="FAILED">Failed</mat-button-toggle>
    </mat-button-toggle-group>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (!loading() && statements().length === 0) {
      <app-empty-state
        icon="receipt_long"
        [title]="filter() ? 'No statements with this status' : 'No statements yet'"
        message="Upload a CSV, XLS, XLSX or PDF statement to get started."
      >
        <a mat-flat-button routerLink="/upload">Upload a statement</a>
      </app-empty-state>
    } @else {
      <div class="table-wrap">
        <table mat-table [dataSource]="statements()">
          <ng-container matColumnDef="account">
            <th mat-header-cell *matHeaderCellDef>Account</th>
            <td mat-cell *matCellDef="let s">
              <a [routerLink]="['/statements', s.id]" class="row-link">{{ accountLabel(s.accountId) }}</a>
            </td>
          </ng-container>
          <ng-container matColumnDef="period">
            <th mat-header-cell *matHeaderCellDef>Period</th>
            <td mat-cell *matCellDef="let s">{{ s.periodStart ? s.periodStart + ' – ' + s.periodEnd : '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="file">
            <th mat-header-cell *matHeaderCellDef>File</th>
            <td mat-cell *matCellDef="let s">{{ s.fileType }}</td>
          </ng-container>
          <ng-container matColumnDef="uploaded">
            <th mat-header-cell *matHeaderCellDef>Uploaded</th>
            <td mat-cell *matCellDef="let s">{{ s.createdAt | date: 'd MMM y, HH:mm' }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let s"><app-status-chip [status]="s.status" /></td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns" class="row" (click)="open(row)"></tr>
        </table>
      </div>
    }
  `,
  styles: `
    .header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
    mat-button-toggle-group { margin-bottom: 16px; }
    .table-wrap { overflow-x: auto; }
    table { width: 100%; }
    .row { cursor: pointer; }
    .row:hover { background: var(--mat-sys-surface-container-low); }
    .row-link { color: inherit; font-weight: 500; text-decoration: none; }
  `,
})
export class StatementsPage {
  private readonly statementsApi = inject(StatementsApi);
  private readonly accountsApi = inject(AccountsApi);
  private readonly router = inject(Router);

  /** `?status=` query parameter (bound by withComponentInputBinding). */
  readonly status = input<string | undefined>(undefined);

  protected readonly columns = ['account', 'period', 'file', 'uploaded', 'status'];
  protected readonly statements = signal<Statement[]>([]);
  private readonly accounts = signal<Map<string, Account>>(new Map());
  protected readonly loading = signal(true);
  protected readonly filter = computed<StatementStatus | null>(() => {
    const s = this.status();
    return s && (STATEMENT_STATUSES as readonly string[]).includes(s) ? (s as StatementStatus) : null;
  });

  constructor() {
    effect(() => {
      const status = this.filter();
      untracked(() => this.load(status));
    });
  }

  protected accountLabel(id: string): string {
    return this.accounts().get(id)?.label ?? 'Unknown account';
  }

  protected setFilter(value: string): void {
    void this.router.navigate([], { queryParams: { status: value === 'ALL' ? null : value } });
  }

  protected open(statement: Statement): void {
    void this.router.navigate(['/statements', statement.id]);
  }

  private load(status: StatementStatus | null): void {
    this.loading.set(true);
    forkJoin({
      statements: this.statementsApi.listStatements(status ? { status } : {}),
      accounts: this.accountsApi.listAccounts(),
    }).subscribe({
      next: ({ statements, accounts }) => {
        this.accounts.set(new Map(accounts.map((a) => [a.id, a])));
        this.statements.set(statements);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
