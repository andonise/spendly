import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/** Centered icon + title + message, with projected actions. */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule],
  template: `
    <mat-icon class="icon" aria-hidden="true">{{ icon() }}</mat-icon>
    <h2 class="title">{{ title() }}</h2>
    @if (message()) {
      <p class="message">{{ message() }}</p>
    }
    <div class="actions"><ng-content /></div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 48px 16px; }
    .icon { width: 56px; height: 56px; font-size: 56px; color: var(--mat-sys-primary); }
    .title { font: var(--mat-sys-headline-small); margin: 16px 0 8px; }
    .message { font: var(--mat-sys-body-large); color: var(--mat-sys-on-surface-variant); max-width: 520px; margin: 0; }
    .actions { margin-top: 24px; display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
    .actions:empty { display: none; }
  `,
})
export class EmptyState {
  readonly icon = input('inbox');
  readonly title = input.required<string>();
  readonly message = input<string | null>(null);
}
