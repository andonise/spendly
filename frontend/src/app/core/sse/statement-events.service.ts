import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import {
  Observable,
  Subscription,
  distinctUntilChanged,
  exhaustMap,
  map,
  retry,
  takeWhile,
  throwError,
  timer,
} from 'rxjs';
import { handledErrors } from '../api/api-options';
import { StatementsApi } from '../api/statements.api';
import { APP_ENV } from '../config/app-env';
import { Statement, StatementStatusEvent, Uuid, isTerminalStatus } from '../models/api.models';

/** Creates EventSource instances; null when the platform has none. Overridable in tests. */
export const EVENT_SOURCE_FACTORY = new InjectionToken<((url: string) => EventSource) | null>(
  'EVENT_SOURCE_FACTORY',
  {
    providedIn: 'root',
    factory: () => (typeof EventSource === 'undefined' ? null : (url: string) => new EventSource(url)),
  },
);

export interface SseOptions {
  /** How many times to reconnect the EventSource before falling back to polling. */
  maxReconnects: number;
  reconnectDelayMs: number;
}

export const SSE_OPTIONS = new InjectionToken<SseOptions>('SSE_OPTIONS', {
  providedIn: 'root',
  factory: () => ({ maxReconnects: 2, reconnectDelayMs: 1000 }),
});

export type StatusTransport = 'sse' | 'polling';

/**
 * Status updates for one statement (plan section 6):
 * - real backend: EventSource on GET /api/statements/{id}/events (`event: status`), reconnects
 *   `maxReconnects` times, then falls back to polling GET /api/statements/{id};
 * - mock mode (or no EventSource): polling only, because EventSource bypasses HttpClient
 *   interceptors and so can't reach the mock API.
 * Every stream completes after a terminal status (CATEGORIZED, FAILED).
 */
@Injectable({ providedIn: 'root' })
export class StatementEventsService {
  private readonly env = inject(APP_ENV);
  private readonly api = inject(StatementsApi);
  private readonly eventSourceFactory = inject(EVENT_SOURCE_FACTORY);
  private readonly options = inject(SSE_OPTIONS);

  /** Transport used by the most recent stream (useful for diagnostics and tests). */
  readonly transport = signal<StatusTransport | null>(null);

  watch(statementId: Uuid): Observable<StatementStatusEvent> {
    const useSse = !this.env.mock.statements && this.eventSourceFactory !== null;
    const source$ = useSse ? this.viaEventSource(statementId) : this.viaPolling(statementId);
    return source$.pipe(
      distinctUntilChanged((a, b) => a.status === b.status && a.lastCompletedStep === b.lastCompletedStep),
    );
  }

  /** Polls GET /api/statements/{id} until a terminal status. Goes through HttpClient (and the mock). */
  viaPolling(statementId: Uuid): Observable<StatementStatusEvent> {
    return timer(0, this.env.pollIntervalMs).pipe(
      exhaustMap(() => {
        this.transport.set('polling');
        // Transient failures retry silently; other errors still show the snackbar.
        return this.api.getStatement(statementId, { context: handledErrors(0, 502, 503, 504) });
      }),
      retry({
        delay: (error: unknown, count) =>
          error instanceof HttpErrorResponse && [0, 502, 503, 504].includes(error.status) && count <= 10
            ? timer(this.env.pollIntervalMs)
            : throwError(() => error),
      }),
      map(toStatusEvent),
      distinctUntilChanged((a, b) => a.status === b.status && a.lastCompletedStep === b.lastCompletedStep),
      takeWhile((e) => !isTerminalStatus(e.status), true),
    );
  }

  private viaEventSource(statementId: Uuid): Observable<StatementStatusEvent> {
    const url = this.api.streamStatementEventsUrl(statementId);
    const factory = this.eventSourceFactory!;
    const { maxReconnects, reconnectDelayMs } = this.options;

    return new Observable<StatementStatusEvent>((subscriber) => {
      let source: EventSource | null = null;
      let failures = 0;
      let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
      let fallback: Subscription | undefined;

      const closeSource = () => {
        source?.close();
        source = null;
      };

      const onMessage = (message: MessageEvent<string>) => {
        const event = parseStatusEvent(message.data);
        if (!event) return;
        failures = 0;
        subscriber.next(event);
        if (isTerminalStatus(event.status)) {
          closeSource();
          subscriber.complete();
        }
      };

      const connect = () => {
        this.transport.set('sse');
        source = factory(url);
        source.addEventListener('status', onMessage as EventListener);
        source.onmessage = onMessage; // tolerate servers that omit `event: status`
        source.onerror = () => {
          closeSource(); // stop the browser's own retry loop; we decide what happens next
          if (failures < maxReconnects) {
            failures++;
            reconnectTimer = setTimeout(connect, reconnectDelayMs);
          } else {
            fallback = this.viaPolling(statementId).subscribe(subscriber);
          }
        };
      };

      connect();
      return () => {
        clearTimeout(reconnectTimer);
        closeSource();
        fallback?.unsubscribe();
      };
    });
  }
}

export function toStatusEvent(statement: Statement): StatementStatusEvent {
  return {
    statementId: statement.id,
    status: statement.status,
    lastCompletedStep: statement.lastCompletedStep ?? null,
    error: statement.error ?? null,
    occurredAt: statement.updatedAt,
  };
}

function parseStatusEvent(data: string): StatementStatusEvent | null {
  try {
    const parsed = JSON.parse(data) as StatementStatusEvent;
    return parsed && typeof parsed.status === 'string' ? parsed : null;
  } catch {
    return null;
  }
}
