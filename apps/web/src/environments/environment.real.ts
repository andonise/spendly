import { Environment } from './environment.model';

/** All endpoint groups go to the real backend (`ng serve --configuration real`). */
export const environment: Environment = {
  production: false,
  apiBaseUrl: '',
  mock: {
    accounts: false,
    statements: false,
    questions: false,
    transactions: false,
    counterparties: false,
  },
  mockOptions: {
    latencyMs: 0,
    stepMs: 2500,
  },
  pollIntervalMs: 2000,
};
