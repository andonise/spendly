import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/empty-state';

/** Placeholder until Phase 5 (POST /api/chat/stream, consumed with core/sse/sse-fetch.ts). */
@Component({
  selector: 'app-chat-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, MatButtonModule, RouterLink],
  template: `
    <app-empty-state
      icon="forum"
      title="Chat is coming in Phase 5"
      message="Ask questions like “How much did we spend on restaurants since June?” and get streamed answers."
    >
      <a mat-stroked-button routerLink="/statements">Go to statements</a>
    </app-empty-state>
  `,
})
export class ChatPage {}
