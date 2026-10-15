import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Category } from '../core/api';
import { categoryLabel } from './categories';

@Component({
  selector: 'app-category-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `{{ label() }}`,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      padding: 2px 10px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface);
      white-space: nowrap;
    }
  `,
})
export class CategoryChip {
  readonly category = input.required<Category>();
  protected readonly label = computed(() => categoryLabel(this.category()));
}
