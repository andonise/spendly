import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/empty-state';

/** Placeholder until Phase 3 (/api/learning/aliases, /api/learning/hints). */
@Component({
  selector: 'app-learning-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, MatButtonModule, RouterLink],
  template: `
    <app-empty-state
      icon="school"
      title="Learning is coming in Phase 3"
      message="View, edit and delete the aliases and hints Spendly learns from your answers, and add global hints."
    >
      <a mat-stroked-button routerLink="/statements">Go to statements</a>
    </app-empty-state>
  `,
})
export class LearningPage {}
