import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { environment } from '../../../environments/environment';
import { Environment } from '../../../environments/environment.model';
import { APP_ENV } from '../config/app-env';
import { Statement, StatementStatus, StatementStatusEvent } from '../models/api.models';
import { EVENT_SOURCE_FACTORY, SSE_OPTIONS, StatementEventsService } from './statement-events.service';

/** Minimal controllable EventSource. */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  closed = false;
  onerror: (() => void) | null = null;
  onmessage: ((e: MessageEvent<string>) => void) | null = null;
  private listeners = new Map<string, ((e: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }
  addEventListener(type: string, fn: (e: MessageEvent<string>) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }
  close(): void {
    this.closed = true;
  }
  emitStatus(status: StatementStatus): void {
    const data = JSON.stringify({ statementId: 's1', status, occurredAt: '2026-10-15T10:00:00Z' });
    for (const fn of this.listeners.get('status') ?? []) fn(new MessageEvent('status', { data }));
  }
  fail(): void {
    this.onerror?.();
  }
}

function statement(status: StatementStatus): Statement {
  return {
    id: 's1', accountId: 'a1', fileType: 'CSV', status, lastCompletedStep: null,
    createdAt: '2026-10-15T10:00:00Z', updatedAt: '2026-10-15T10:00:00Z',
  };
}

function setup(mockStatements: boolean) {
  FakeEventSource.instances = [];
  const env: Environment = {
    ...environment,
    mock: { accounts: false, statements: mockStatements, questions: false, transactions: false, counterparties: false },
    pollIntervalMs: 10,
  };
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_ENV, useValue: env },
      { provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new FakeEventSource(url) as unknown as EventSource },
      { provide: SSE_OPTIONS, useValue: { maxReconnects: 1, reconnectDelayMs: 0 } },
      { provide: MatSnackBar, useValue: { open: vi.fn() } },
    ],
  });
  return {
    service: TestBed.inject(StatementEventsService),
    httpMock: TestBed.inject(HttpTestingController),
  };
}

describe('StatementEventsService', () => {
  it('uses EventSource against the real backend and closes on a terminal status', () => {
    const { service, httpMock } = setup(false);
    const events: StatementStatusEvent[] = [];
    let completed = false;
    service.watch('s1').subscribe({ next: (e) => events.push(e), complete: () => (completed = true) });

    const es = FakeEventSource.instances[0];
    expect(es.url).toBe('/api/statements/s1/events');
    expect(service.transport()).toBe('sse');
    es.emitStatus('PARSED');
    es.emitStatus('PARSED'); // duplicates are dropped
    es.emitStatus('CATEGORIZING');
    es.emitStatus('CATEGORIZED');

    expect(events.map((e) => e.status)).toEqual(['PARSED', 'CATEGORIZING', 'CATEGORIZED']);
    expect(es.closed).toBe(true);
    expect(completed).toBe(true);
    httpMock.verify(); // no polling happened
  });

  it('reconnects once, then falls back to polling GET /api/statements/{id} every interval', async () => {
    const { service, httpMock } = setup(false);
    const events: StatementStatusEvent[] = [];
    let completed = false;
    service.watch('s1').subscribe({ next: (e) => events.push(e), complete: () => (completed = true) });

    FakeEventSource.instances[0].fail();
    expect(FakeEventSource.instances[0].closed).toBe(true);
    await vi.waitFor(() => expect(FakeEventSource.instances.length).toBe(2)); // reconnect attempt
    FakeEventSource.instances[1].fail(); // still broken → polling

    await vi.waitFor(() => httpMock.expectOne('/api/statements/s1').flush(statement('PARSED')));
    expect(service.transport()).toBe('polling');
    await vi.waitFor(() => httpMock.expectOne('/api/statements/s1').flush(statement('NEEDS_INPUT')));
    await vi.waitFor(() => httpMock.expectOne('/api/statements/s1').flush(statement('FAILED')));

    expect(events.map((e) => e.status)).toEqual(['PARSED', 'NEEDS_INPUT', 'FAILED']);
    expect(completed).toBe(true);
    expect(FakeEventSource.instances.length).toBe(2);
    httpMock.verify();
  });

  it('polls (and never opens an EventSource) when statements are mocked', async () => {
    const { service, httpMock } = setup(true);
    const events: StatementStatusEvent[] = [];
    service.watch('s1').subscribe((e) => events.push(e));

    await vi.waitFor(() => httpMock.expectOne('/api/statements/s1').flush(statement('CATEGORIZED')));
    expect(FakeEventSource.instances.length).toBe(0);
    expect(events.map((e) => e.status)).toEqual(['CATEGORIZED']);
    httpMock.verify();
  });

  it('closes the EventSource when the subscriber unsubscribes', () => {
    const { service } = setup(false);
    const sub = service.watch('s1').subscribe();
    sub.unsubscribe();
    expect(FakeEventSource.instances[0].closed).toBe(true);
  });
});
