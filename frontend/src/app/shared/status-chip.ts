import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { StatementStatus } from '../core/api';

const LABELS: Record<StatementStatus, string> = {
  UPLOADED: 'Uploaded',
  PARSED: 'Parsed',
  CATEGORIZING: 'Categorizing',
  NEEDS_INPUT: 'Needs your input',
  CATEGORIZED: 'Done',
  FAILED: 'Failed',
};

/** Statement status as a small tinted pill. */
@Component({
  selector: 'app-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-status]': 'status()' },
  template: `{{ label() }}`,
  styles: `
    :host {
      display: inline-block; padding: 2px 10px; border-radius: 999px; white-space: nowrap;
      font: var(--mat-sys-label-medium);
      background: var(--mat-sys-surface-container-high); color: var(--mat-sys-on-surface-variant);
    }
    :host([data-status='NEEDS_INPUT']) { background: var(--mat-sys-tertiary-container); color: var(--mat-sys-on-tertiary-container); }
    :host([data-status='CATEGORIZED']) { background: var(--mat-sys-primary-container); color: var(--mat-sys-on-primary-container); }
    :host([data-status='FAILED']) { background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container); }
  `,
})
export class StatusChip {
  readonly status = input.required<StatementStatus>();
  protected readonly label = computed(() => LABELS[this.status()]);
}
