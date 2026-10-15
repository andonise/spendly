import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { ACCOUNT_TYPES, Account, AccountCreate, AccountType, AccountsApi } from '../../core/api';
import { ibanValidator } from '../../shared/iban';

export const ACCOUNT_TYPE_LABELS: Record<AccountType, string> = {
  CURRENT: 'Current account',
  SAVINGS: 'Savings',
  CREDIT_CARD: 'Credit card',
  E_WALLET: 'E-wallet',
};

function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

@Component({
  selector: 'app-accounts-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatMenuModule, MatProgressBarModule, MatSelectModule, MatTableModule,
  ],
  templateUrl: './accounts-page.html',
  styleUrl: './accounts-page.scss',
})
export class AccountsPage implements OnInit {
  private readonly api = inject(AccountsApi);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly accountTypes = ACCOUNT_TYPES;
  protected readonly typeLabels = ACCOUNT_TYPE_LABELS;
  protected readonly columns = ['label', 'type', 'iban', 'card', 'activeFrom', 'state', 'actions'];

  protected readonly accounts = signal<Account[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly busyId = signal<string | null>(null);

  /** The IBAN lives only in this control until it is sent; the form is reset after saving. */
  protected readonly form = this.fb.group({
    label: ['', [Validators.required, Validators.maxLength(80)]],
    bank: ['', [Validators.required, Validators.maxLength(100)]],
    type: ['CURRENT' as AccountType, Validators.required],
    currency: ['RON', [Validators.required, Validators.pattern(/^[A-Z]{3}$/)]],
    iban: ['', [Validators.required, ibanValidator]],
    cardLast4: ['', Validators.pattern(/^[0-9]{4}$/)],
    activeFrom: [today(), Validators.required],
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.listAccounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  register(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body: AccountCreate = {
      label: v.label.trim(),
      bank: v.bank.trim(),
      type: v.type,
      currency: v.currency.toUpperCase(),
      iban: v.iban,
      cardLast4: v.cardLast4 || null,
      activeFrom: v.activeFrom,
    };
    this.saving.set(true);
    this.api.createAccount(body).subscribe({
      next: (account) => {
        this.saving.set(false);
        this.resetForm();
        this.accounts.update((list) => [...list, account].sort((a, b) => a.label.localeCompare(b.label)));
        this.snackBar.open(`Registered “${account.label}” (${account.ibanMasked}).`, 'OK');
      },
      error: () => this.saving.set(false),
    });
  }

  close(account: Account): void {
    if (!confirm(`Close “${account.label}” as of today? Its statements are kept.`)) return;
    this.busyId.set(account.id);
    this.api.updateAccount(account.id, { closedAt: today() }).subscribe({
      next: (updated) => {
        this.busyId.set(null);
        this.accounts.update((list) => list.map((a) => (a.id === updated.id ? updated : a)));
        this.snackBar.open(`“${updated.label}” is closed.`, 'OK');
      },
      error: () => this.busyId.set(null),
    });
  }

  remove(account: Account): void {
    if (!confirm(`Delete “${account.label}”? This only works for accounts without statements.`)) return;
    this.busyId.set(account.id);
    // A 409 (account still has statements) is shown by the error interceptor with the backend's detail.
    this.api.deleteAccount(account.id).subscribe({
      next: () => {
        this.busyId.set(null);
        this.accounts.update((list) => list.filter((a) => a.id !== account.id));
        this.snackBar.open(`“${account.label}” was deleted.`, 'OK');
      },
      error: () => this.busyId.set(null),
    });
  }

  protected typeLabel(type: AccountType): string {
    return ACCOUNT_TYPE_LABELS[type] ?? type;
  }

  protected ibanError(): string {
    const errors = this.form.controls.iban.errors;
    if (errors?.['required']) return 'Enter the IBAN.';
    switch (errors?.['iban']) {
      case 'format':
        return 'This does not look like an IBAN (e.g. RO54 SPND 0000 1111 2222 3333).';
      case 'length':
        return 'The IBAN has the wrong length for its country.';
      case 'checksum':
        return 'The check digits do not match; check for typos.';
    }
    return '';
  }

  private resetForm(): void {
    this.form.reset({ type: 'CURRENT', currency: 'RON', activeFrom: today() });
  }
}
