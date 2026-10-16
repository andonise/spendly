import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { EnvironmentProviders, Provider } from '@angular/core';
import { environment } from '../../../environments/environment';
import { Environment } from '../../../environments/environment.model';
import { APP_ENV } from '../config/app-env';
import { mockApiInterceptor } from './mock-api.interceptor';
import { MOCK_CLOCK } from './mock-backend';

/** A clock tests can move forward by hand. */
export class ManualClock {
  constructor(public now = Date.parse('2026-10-15T10:00:00Z')) {}
  advance(ms: number): void {
    this.now += ms;
  }
}

/**
 * Providers for running code against the in-memory mock API in unit tests:
 * every group mocked, no latency, `stepMs` per pipeline step, optional manual clock.
 */
export function provideMockApiForTests(
  options: { stepMs?: number; pollIntervalMs?: number; clock?: ManualClock; interceptors?: typeof mockApiInterceptor[] } = {},
): (Provider | EnvironmentProviders)[] {
  const env: Environment = {
    ...environment,
    mock: { accounts: true, statements: true, questions: true, transactions: true, counterparties: true },
    mockOptions: { latencyMs: 0, stepMs: options.stepMs ?? 1000 },
    pollIntervalMs: options.pollIntervalMs ?? 2000,
  };
  const providers: (Provider | EnvironmentProviders)[] = [
    provideHttpClient(withInterceptors([...(options.interceptors ?? []), mockApiInterceptor])),
    { provide: APP_ENV, useValue: env },
  ];
  if (options.clock) {
    const clock = options.clock;
    providers.push({ provide: MOCK_CLOCK, useValue: () => clock.now });
  }
  return providers;
}

/** A synthetic statement file (content is irrelevant to the mock; name + size matter). */
export function sampleFile(name = 'generic-bank-2026-08.csv', size = 2048): File {
  return new File(['x'.repeat(size)], name, { type: 'text/csv' });
}
