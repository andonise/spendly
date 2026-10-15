import { HttpErrorResponse } from '@angular/common/http';
import { Problem } from '../models/api.models';

/** Extracts an RFC 7807 problem from any HTTP error, with sensible fallbacks. */
export function toProblem(error: unknown): Problem {
  if (!(error instanceof HttpErrorResponse)) {
    return { title: 'Unexpected error', detail: error instanceof Error ? error.message : undefined };
  }
  if (error.status === 0) {
    return { status: 0, title: 'Cannot reach the server', detail: 'Check that the backend is running.' };
  }
  let body: unknown = error.error;
  if (typeof body === 'string') {
    try {
      body = JSON.parse(body);
    } catch {
      body = { detail: body };
    }
  }
  const problem = body && typeof body === 'object' ? (body as Problem) : {};
  return {
    ...problem,
    status: problem.status ?? error.status,
    title: problem.title ?? (error.statusText || `HTTP ${error.status}`),
  };
}

/** One-line human message: "Title: detail" (or just the title). */
export function problemMessage(problem: Problem): string {
  const fieldErrors = (problem.errors ?? [])
    .map((e) => [e.field, e.message].filter(Boolean).join(' '))
    .filter(Boolean);
  const detail = problem.detail ?? (fieldErrors.length ? fieldErrors.join('; ') : undefined);
  return detail ? `${problem.title ?? 'Error'}: ${detail}` : (problem.title ?? 'Error');
}
