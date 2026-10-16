import { HttpErrorResponse, HttpHeaders, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, delay, dematerialize, materialize, of, throwError } from 'rxjs';
import { APP_ENV } from '../config/app-env';
import { MockBackend, MockHttpError, apiGroupOf } from './mock-backend';

const STATUS_TEXT: Record<number, string> = {
  200: 'OK', 201: 'Created', 202: 'Accepted', 204: 'No Content', 400: 'Bad Request', 404: 'Not Found',
  409: 'Conflict', 413: 'Payload Too Large', 415: 'Unsupported Media Type', 500: 'Internal Server Error',
  501: 'Not Implemented',
};

/**
 * In-memory mock of the Spendly API. Answers requests for every endpoint group that is
 * mocked in the environment (`mock.accounts`, `mock.statements`, `mock.questions`,
 * `mock.transactions`, `mock.counterparties`) and
 * passes everything else through to the real backend.
 * Register it LAST so the other interceptors (errors, …) see its responses.
 */
export const mockApiInterceptor: HttpInterceptorFn = (req, next) => {
  const env = inject(APP_ENV);
  const base = env.apiBaseUrl.replace(/\/$/, '');
  const url = new URL(req.url, 'http://mock.invalid');
  const path = base && req.url.startsWith(base) ? new URL(req.url.slice(base.length), 'http://mock.invalid').pathname : url.pathname;
  const group = apiGroupOf(path);
  if (!group || !env.mock[group]) return next(req);

  const backend = inject(MockBackend);
  const latency = env.mockOptions.latencyMs;

  let result$: Observable<HttpResponse<unknown>>;
  try {
    // Merge query params that were written into the URL with HttpParams.
    let params = req.params;
    url.searchParams.forEach((value, key) => (params = params.set(key, value)));
    const res = backend.handle(req.method, path, params, req.body);
    result$ = of(
      new HttpResponse({
        status: res.status,
        statusText: STATUS_TEXT[res.status] ?? '',
        body: res.body,
        url: req.url,
        headers: new HttpHeaders({ 'Content-Type': 'application/json', ...(res.headers ?? {}) }),
      }),
    );
  } catch (error) {
    const status = error instanceof MockHttpError ? error.status : 500;
    const body =
      error instanceof MockHttpError
        ? error.problem
        : { type: 'about:blank', title: 'Internal Server Error', status: 500, detail: String(error) };
    result$ = throwError(
      () =>
        new HttpErrorResponse({
          status,
          statusText: STATUS_TEXT[status] ?? '',
          error: body,
          url: req.url,
          headers: new HttpHeaders({ 'Content-Type': 'application/problem+json' }),
        }),
    );
  }
  // Delay errors too (materialize → delay → dematerialize), like a real network round-trip.
  return latency > 0 ? result$.pipe(materialize(), delay(latency), dematerialize()) : result$;
};
