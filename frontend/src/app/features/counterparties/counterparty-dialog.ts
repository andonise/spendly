import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { CounterpartiesApi, Counterparty, CounterpartyRef } from '../../core/api';
import { shortToken } from '../../shared/transfers';

export interface CounterpartyDialogData {
  counterparty: CounterpartyRef;
  /** A sanitized description, to help the user recognise it. */
  example?: string | null;
  /** Label of the registered account whose IBAN this is (then "own account" can't be unset). */
  ownAccountLabel?: string | null;
}

/**
 * "Label counterparty": label + "This is one of my accounts" → PUT /api/counterparties/{token}.
 * Closes with the updated Counterparty, or undefined when cancelled. Errors are shown by the
 * global error interceptor and the dialog stays open.
 */
@Component({
  selector: 'app-counterparty-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Label counterparty</h2>
    <mat-dialog-content>
      @if (data.example) {
        <p class="example">{{ data.example }}</p>
      }
      <p class="muted small">
        {{ data.counterparty.kind === 'PERSON' ? 'Person' : 'IBAN' }} · <span class="mono">{{ token }}</span>
        @if (data.ownAccountLabel) { · your account “{{ data.ownAccountLabel }}” }
      </p>
      <mat-form-field class="full">
        <mat-label>Label</mat-label>
        <input matInput name="label" maxlength="80" [(ngModel)]="label" placeholder="e.g. Landlord" cdkFocusInitial />
        <mat-hint align="end">{{ label.length }}/80</mat-hint>
      </mat-form-field>
      <mat-checkbox name="own" [(ngModel)]="isOwnAccount" [disabled]="!!data.counterparty.ownAccountId">
        This is one of my accounts
      </mat-checkbox>
      <p class="muted small">Its transactions then count as internal transfers and are left out of spending and income.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="button" class="save" [disabled]="saving()" (click)="save()">
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
export class CounterpartyDialog {
  protected readonly data = inject<CounterpartyDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<CounterpartyDialog, Counterparty>>(MatDialogRef);
  private readonly api = inject(CounterpartiesApi);

  protected readonly token = shortToken(this.data.counterparty.token);
  protected label = this.data.counterparty.label ?? '';
  protected isOwnAccount = this.data.counterparty.isOwnAccount;
  protected readonly saving = signal(false);

  save(): void {
    if (this.saving()) return;
    this.saving.set(true);
    const label = this.label.trim() || null;
    this.api.updateCounterparty(this.data.counterparty.token, { label, isOwnAccount: this.isOwnAccount }).subscribe({
      next: (updated) => this.ref.close(updated),
      error: () => this.saving.set(false),
    });
  }
}
