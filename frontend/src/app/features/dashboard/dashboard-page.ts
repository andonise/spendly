import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/empty-state';

/** Placeholder until Phase 4 (period analysis) and Phase 5 (recommendations). */
@Component({
  selector: 'app-dashboard-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, MatButtonModule, RouterLink],
  template: `
    <app-empty-state
      icon="space_dashboard"
      title="Dashboard is coming in Phase 4"
      message="Period overview (day / week / month / custom), category charts, top merchants and coverage banners arrive with the period analysis engine. Recommendation cards follow in Phase 5."
    >
      <a mat-stroked-button routerLink="/statements">Go to statements</a>
    </app-empty-state>
  `,
})
export class DashboardPage {}
