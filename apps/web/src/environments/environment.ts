import { Environment } from './environment.model';

/**
 * Default (development + unit test) environment. A group set to `true` is served by the
 * in-memory mock API; `false` goes to the real backend through the dev proxy
 * (proxy.conf.json → http://localhost:8080).
 *
 * Every group is real since the Phase 3 backend (reports since Phase 4); set one to `true` to work on the UI without it.
 */
export const environment: Environment = {
  production: false,
  apiBaseUrl: '',
  mock: {
    accounts: false,
    statements: false,
    questions: false,
    transactions: false,
    counterparties: false,
    reports: false,
  },
  mockOptions: {
    latencyMs: 250,
    stepMs: 2500,
  },
  pollIntervalMs: 2000,
};
