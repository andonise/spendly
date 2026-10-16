import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { catchError, forkJoin, of } from 'rxjs';
import { Account, AccountsApi, CounterpartiesApi, Counterparty, handledErrors } from '../../core/api';
import { EmptyState } from '../../shared/empty-state';
import { counterpartyName, shortToken } from '../../shared/transfers';
import { CounterpartyDialog, CounterpartyDialogData } from './counterparty-dialog';

/** Counterparties seen in transactions (pseudonymized tokens), with labels and the own-account flag. */
@Component({
  selector: 'app-counterparties-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, MatTableModule, MatTooltipModule, EmptyState],
  template: `
    <h1 class="page-title">Counterparties</h1>
    <p class="page-subtitle">
      People and IBANs from your transactions. Label them, or mark one as your own account so its
      transfers are left out of spending.
    </p>

    @if (loading()) {
      <mat-progress-bar mode="indeterminate" />
    } @else if (rows().length === 0) {
      <app-empty-state icon="group" title="No counterparties yet"
        message="They appear once statements with transfers to or from people or IBANs are processed." />
    } @else {
      <div class="table-wrap">
        <table mat-table [dataSource]="rows()">
          <ng-container matColumnDef="label">
            <th mat-header-cell *matHeaderCellDef>Counterparty</th>
            <td mat-cell *matCellDef="let c">
              <div class="name" [class.unlabelled]="!c.label">{{ name(c) }}</div>
              <div class="muted small mono" [matTooltip]="'Pseudonymized token (prefix only)'">{{ short(c.token) }}</div>
            </td>
          </ng-container>
          <ng-container matColumnDef="kind">
            <th mat-header-cell *matHeaderCellDef>Kind</th>
            <td mat-cell *matCellDef="let c">{{ c.kind === 'PERSON' ? 'Person' : c.kind === 'IBAN' ? 'IBAN' : '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="own">
            <th mat-header-cell *matHeaderCellDef>Own account</th>
            <td mat-cell *matCellDef="let c">
              @if (c.isOwnAccount) {
                <span class="own"><mat-icon aria-hidden="true">check_circle</mat-icon> Yes</span>
              } @else {
                <span class="muted">No</span>
              }
            </td>
          </ng-container>
          <ng-container matColumnDef="count">
            <th mat-header-cell *matHeaderCellDef class="num">Transactions</th>
            <td mat-cell *matCellDef="let c" class="num">{{ c.transactionCount }}</td>
          </ng-container>
          <ng-container matColumnDef="lastSeen">
            <th mat-header-cell *matHeaderCellDef>Last seen</th>
            <td mat-cell *matCellDef="let c">{{ c.lastSeen ?? '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="sample">
            <th mat-header-cell *matHeaderCellDef>Example</th>
            <td mat-cell *matCellDef="let c" class="sample">{{ c.sampleDescription ?? '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef><span class="cdk-visually-hidden">Actions</span></th>
            <td mat-cell *matCellDef="let c">
              <button mat-icon-button type="button" class="edit" (click)="edit(c)"
                      matTooltip="Label counterparty" [attr.aria-label]="'Label counterparty ' + name(c)">
                <mat-icon>edit</mat-icon>
              </button>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns" [attr.data-token-prefix]="short(row.token)"></tr>
        </table>
      </div>
    }
  `,
  styles: `
    .table-wrap { overflow-x: auto; }
    table { width: 100%; min-width: 760px; }
    .name { font-weight: 500; }
    .unlabelled { font-weight: 400; font-style: italic; }
    .small { font: var(--mat-sys-body-small); }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .sample { max-width: 280px; word-break: break-word; }
    .own { display: inline-flex; align-items: center; gap: 4px; color: var(--mat-sys-primary); }
    .own mat-icon { font-size: 18px; width: 18px; height: 18px; }
  `,
})
export class CounterpartiesPage implements OnInit {
  private readonly api = inject(CounterpartiesApi);
  private readonly accountsApi = inject(AccountsApi);
  private readonly dialog = inject(MatDialog);

  protected readonly columns = ['label', 'kind', 'own', 'count', 'lastSeen', 'sample', 'actions'];
  protected readonly rows = signal<Counterparty[]>([]);
  protected readonly loading = signal(true);
  private readonly accounts = signal<Map<string, Account>>(new Map());
  protected readonly short = shortToken;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    forkJoin({
      counterparties: this.api.listCounterparties(),
      // Accounts only name own-account rows; the list still works without them.
      accounts: this.accountsApi
        .listAccounts({ context: handledErrors(0, 404, 500, 502, 503, 504) })
        .pipe(catchError(() => of([] as Account[]))),
    }).subscribe({
      next: ({ counterparties, accounts }) => {
        this.accounts.set(new Map(accounts.map((a) => [a.id, a])));
        this.rows.set(counterparties);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected name(c: Counterparty): string {
    return counterpartyName(c, c.ownAccountId ? this.accounts().get(c.ownAccountId)?.label : null);
  }

  edit(c: Counterparty): void {
    const data: CounterpartyDialogData = {
      counterparty: c,
      example: c.sampleDescription,
      ownAccountLabel: c.ownAccountId ? (this.accounts().get(c.ownAccountId)?.label ?? null) : null,
    };
    this.dialog
      .open(CounterpartyDialog, { data, width: '440px', maxWidth: 'calc(100vw - 32px)' })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) this.load();
      });
  }
}
