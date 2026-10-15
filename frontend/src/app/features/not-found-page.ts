import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../shared/empty-state';

/** Fallback for unknown routes. */
@Component({
  selector: 'app-not-found-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, MatButtonModule, RouterLink],
  template: `
    <app-empty-state
      icon="explore_off"
      title="Page not found"
      message="The page you are looking for does not exist."
    >
      <a mat-stroked-button routerLink="/statements">Go to statements</a>
    </app-empty-state>
  `,
})
export class NotFoundPage {}
