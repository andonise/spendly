import { Environment } from './environment.model';

/** Production build: real backend only; the mock API is never enabled. */
export const environment: Environment = {
  production: true,
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
    latencyMs: 0,
    stepMs: 2500,
  },
  pollIntervalMs: 2000,
};
