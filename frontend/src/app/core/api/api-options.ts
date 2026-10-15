import { HttpContext, HttpContextToken } from '@angular/common/http';
import { inject } from '@angular/core';
import { APP_ENV } from '../config/app-env';

/**
 * HTTP statuses the caller handles itself; the error interceptor shows no snackbar for them.
 * Example: the questions form handles 409 on submitAnswers with a friendly message.
 */
export const HANDLED_ERROR_STATUSES = new HttpContextToken<readonly number[]>(() => []);

/** Per-call options every API method accepts (same idea as openapi-generator's `options`). */
export interface ApiCallOptions {
  context?: HttpContext;
}

/** Builds an HttpContext that tells the error interceptor to stay silent for `statuses`. */
export function handledErrors(...statuses: number[]): HttpContext {
  return new HttpContext().set(HANDLED_ERROR_STATUSES, statuses);
}

/** Resolves `/api/...` paths against the configured base URL. Call in an injection context. */
export function injectApiUrl(): (path: string) => string {
  const base = inject(APP_ENV).apiBaseUrl.replace(/\/$/, '');
  return (path: string) => `${base}${path}`;
}
