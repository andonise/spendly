import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { HANDLED_ERROR_STATUSES } from '../api/api-options';
import { problemMessage, toProblem } from './problem';

/**
 * Global HTTP error handling: RFC 7807 problem → Material snackbar with `title: detail`.
 * The error is always rethrown so callers can still react. Callers that handle a status
 * themselves (e.g. 409 on submitAnswers) opt out with `handledErrors(409)`.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const snackBar = inject(MatSnackBar);
  const handled = req.context.get(HANDLED_ERROR_STATUSES);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && !handled.includes(error.status)) {
        snackBar.open(problemMessage(toProblem(error)), 'Dismiss', {
          duration: 6000,
          panelClass: 'app-snackbar-error',
        });
      }
      return throwError(() => error);
    }),
  );
};
