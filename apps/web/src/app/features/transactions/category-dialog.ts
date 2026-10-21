import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { Category, Transaction, TransactionsApi } from '../../core/api';
import { CATEGORY_OPTIONS } from '../../shared/categories';
import { MoneyPipe } from '../../shared/money.pipe';

/**
 * "Change category" → PATCH /api/transactions/{id}/category. Closes with the updated Transaction, or undefined when
 * cancelled. Errors (e.g. 409 for a statement that is not categorized yet) are shown by the global error interceptor
 * and the dialog stays open.
 */
@Component({
  selector: 'app-category-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule, MatSelectModule, MoneyPipe],
  template: `
    <h2 mat-dialog-title>Change category</h2>
    <mat-dialog-content>
      <p class="example">{{ data.description }}</p>
      <p class="muted small">{{ data.bookingDate }} · {{ data.amount | money: data.currency }}</p>
      <mat-form-field class="full">
        <mat-label>Category</mat-label>
        <mat-select name="category" [(ngModel)]="category" cdkFocusInitial>
          @for (c of options; track c.value) {
            <mat-option [value]="c.value">{{ c.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <mat-checkbox name="future" [(ngModel)]="applyToFuture">Apply to future transactions from this merchant</mat-checkbox>
      <p class="muted small">Reports covering {{ data.bookingDate }} update automatically.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="button" class="save" [disabled]="saving() || !category || category === data.category" (click)="save()">
        {{ saving() ? 'Saving…' : 'Save' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .full { width: 100%; margin-top: 8px; }
    .example { font: var(--mat-sys-title-small); word-break: break-word; margin: 0 0 4px; }
    .small { font: var(--mat-sys-body-small); }
  `,
})
export class CategoryDialog {
  protected readonly data = inject<Transaction>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<CategoryDialog, Transaction>>(MatDialogRef);
  private readonly api = inject(TransactionsApi);

  /** INTERNAL_TRANSFER is decided by transfer detection, never set by hand. */
  protected readonly options = CATEGORY_OPTIONS.filter((c) => c.value !== 'INTERNAL_TRANSFER');
  protected category: Category | null = this.data.category ?? null;
  protected applyToFuture = false;
  protected readonly saving = signal(false);

  save(): void {
    if (this.saving() || !this.category) return;
    this.saving.set(true);
    this.api.updateTransactionCategory(this.data.id, { category: this.category, applyToFuture: this.applyToFuture }).subscribe({
      next: (updated) => this.ref.close(updated),
      error: () => this.saving.set(false),
    });
  }
}
