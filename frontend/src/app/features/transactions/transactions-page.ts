import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/empty-state';

/** Placeholder until Phase 4 (GET /api/transactions, PATCH /api/transactions/{id}/category). */
@Component({
  selector: 'app-transactions-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, MatButtonModule, RouterLink],
  template: `
    <app-empty-state
      icon="list_alt"
      title="Transactions are coming in Phase 4"
      message="A filterable, sortable table across all accounts with inline category changes (optionally saved as a learning) and transfer badges."
    >
      <a mat-stroked-button routerLink="/statements">Go to statements</a>
    </app-empty-state>
  `,
})
export class TransactionsPage {}
