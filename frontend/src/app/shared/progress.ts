import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';

/** Indeterminate progress with a message, used while the pipeline runs. */
@Component({
  selector: 'app-progress',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatProgressBarModule],
  template: `
    <p class="text" role="status" aria-live="polite">{{ text() }}</p>
    <mat-progress-bar mode="indeterminate" />
    @if (detail()) {
      <p class="detail">{{ detail() }}</p>
    }
  `,
  styles: `
    :host { display: block; padding: 24px 0; max-width: 560px; }
    .text { font: var(--mat-sys-title-medium); margin: 0 0 12px; }
    .detail { font: var(--mat-sys-body-medium); color: var(--mat-sys-on-surface-variant); margin: 12px 0 0; }
  `,
})
export class Progress {
  readonly text = input.required<string>();
  readonly detail = input<string | null>(null);
}
