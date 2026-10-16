import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, RouterLink } from '@angular/router';
import { Account, AccountsApi, StatementsApi } from '../../core/api';
import { OpenQuestionsStore } from '../../core/state/open-questions.store';

export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
export const ACCEPTED_EXTENSIONS = ['csv', 'xls', 'xlsx', 'pdf'];

/** Client-side file check (the backend re-validates: 413 / 415). */
export function fileProblem(file: File): string | null {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!ACCEPTED_EXTENSIONS.includes(ext)) return 'Choose a CSV, XLS, XLSX or PDF file.';
  if (file.size > MAX_UPLOAD_BYTES) return 'The file is larger than 20 MB.';
  if (file.size === 0) return 'The file is empty.';
  return null;
}

@Component({
  selector: 'app-upload-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DecimalPipe, FormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatIconModule,
    MatProgressBarModule, MatSelectModule,
  ],
  templateUrl: './upload-page.html',
  styleUrl: './upload-page.scss',
})
export class UploadPage implements OnInit {
  private readonly accountsApi = inject(AccountsApi);
  private readonly statementsApi = inject(StatementsApi);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly questions = inject(OpenQuestionsStore);

  protected readonly accept = ACCEPTED_EXTENSIONS.map((e) => '.' + e).join(',');
  protected readonly accounts = signal<Account[]>([]);
  protected readonly accountsLoaded = signal(false);
  protected readonly accountId = signal<string | null>(null);
  protected readonly file = signal<File | null>(null);
  protected readonly fileError = signal<string | null>(null);
  protected readonly dragging = signal(false);
  protected readonly uploading = signal(false);

  protected readonly canUpload = computed(
    () => !!this.accountId() && !!this.file() && !this.fileError() && !this.uploading(),
  );

  ngOnInit(): void {
    this.accountsApi.listAccounts().subscribe({
      next: (accounts) => {
        const open = accounts.filter((a) => !a.closedAt);
        this.accounts.set(open);
        if (open.length === 1) this.accountId.set(open[0].id);
        this.accountsLoaded.set(true);
      },
      error: () => this.accountsLoaded.set(true),
    });
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
    this.dragging.set(true);
  }

  protected onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.setFile(file);
  }

  protected onPick(input: HTMLInputElement): void {
    const file = input.files?.[0];
    if (file) this.setFile(file);
    input.value = ''; // allow picking the same file again
  }

  setFile(file: File): void {
    this.file.set(file);
    this.fileError.set(fileProblem(file));
  }

  upload(): void {
    const accountId = this.accountId();
    const file = this.file();
    if (!accountId || !file || !this.canUpload()) return;
    this.uploading.set(true);
    this.statementsApi.uploadStatement(accountId, file).subscribe({
      next: (res) => {
        this.uploading.set(false);
        if (res.duplicate) {
          this.snackBar.open('This file was already uploaded for this account. Showing the existing statement.', 'OK', {
            duration: 6000,
          });
        }
        this.questions.refresh();
        void this.router.navigate(['/statements', res.statementId]);
      },
      error: () => this.uploading.set(false),
    });
  }
}
