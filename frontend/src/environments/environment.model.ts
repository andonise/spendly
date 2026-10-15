/** Endpoint groups that can be mocked independently (they match the OpenAPI tags). */
export type ApiGroup = 'accounts' | 'statements' | 'questions';

export interface Environment {
  production: boolean;
  /** Prefix for API calls. Empty = same origin (the dev server proxies /api to the backend). */
  apiBaseUrl: string;
  /** true = served by the in-memory mock API, false = sent to the real backend. */
  mock: Record<ApiGroup, boolean>;
  /** Mock API timings (ignored for real groups). */
  mockOptions: {
    /** Simulated network latency per request, in ms. */
    latencyMs: number;
    /** Time each simulated pipeline step takes (UPLOADED → PARSED → …), in ms. */
    stepMs: number;
  };
  /** Polling interval used when SSE is unavailable, fails, or is mocked, in ms. */
  pollIntervalMs: number;
}
