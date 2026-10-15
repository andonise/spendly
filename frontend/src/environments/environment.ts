import { Environment } from './environment.model';

/**
 * Default (development + unit test) environment. A group set to `true` is served by the
 * in-memory mock API; `false` goes to the real backend through the dev proxy
 * (proxy.conf.json → http://localhost:8080).
 *
 * accounts + statements are real since the Phase 1 backend implements them. questions stays
 * mocked until Phase 3 adds /answers and /api/questions (the badge count is mock data until then).
 */
export const environment: Environment = {
  production: false,
  apiBaseUrl: '',
  mock: {
    accounts: false,
    statements: false,
    questions: true,
  },
  mockOptions: {
    latencyMs: 250,
    stepMs: 2500,
  },
  pollIntervalMs: 2000,
};
