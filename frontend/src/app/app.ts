import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { APP_ENV, ApiGroup } from './core/config/app-env';
import { OpenQuestionsStore } from './core/state/open-questions.store';

interface NavItem {
  path: string;
  label: string;
  icon: string;
}

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule, MatSidenavModule, MatListModule,
    MatIconModule, MatButtonModule, MatTooltipModule,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private readonly env = inject(APP_ENV);
  private readonly store = inject(OpenQuestionsStore);
  protected readonly openQuestions = this.store.count;

  protected readonly nav: NavItem[] = [
    { path: '/dashboard', label: 'Dashboard', icon: 'space_dashboard' },
    { path: '/accounts', label: 'Accounts', icon: 'account_balance' },
    { path: '/upload', label: 'Upload', icon: 'upload_file' },
    { path: '/statements', label: 'Statements', icon: 'receipt_long' },
    { path: '/transactions', label: 'Transactions', icon: 'list_alt' },
    { path: '/learning', label: 'Learning', icon: 'school' },
    { path: '/chat', label: 'Chat', icon: 'forum' },
  ];

  protected readonly handset = toSignal(
    inject(BreakpointObserver).observe('(max-width: 899px)').pipe(map((r) => r.matches)),
    { initialValue: false },
  );

  /** Endpoint groups served by the in-memory mock (shown as a toolbar hint). */
  protected readonly mockedGroups = computed(() =>
    (Object.keys(this.env.mock) as ApiGroup[]).filter((g) => this.env.mock[g]),
  );

  ngOnInit(): void {
    this.store.refresh();
  }
}
