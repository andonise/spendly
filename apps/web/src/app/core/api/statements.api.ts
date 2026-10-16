import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Statement, StatementStatus, UploadAccepted, Uuid } from '../models/api.models';
import { ApiCallOptions, injectApiUrl } from './api-options';

/**
 * OpenAPI tag `statements`. Method names = operationIds.
 * `streamStatementEvents` (SSE) lives in StatementEventsService, which also handles fallback;
 * this class only exposes its URL.
 */
@Injectable({ providedIn: 'root' })
export class StatementsApi {
  private readonly http = inject(HttpClient);
  private readonly url = injectApiUrl();

  listStatements(
    query: { accountId?: Uuid; status?: StatementStatus } = {},
    options?: ApiCallOptions,
  ): Observable<Statement[]> {
    let params = new HttpParams();
    if (query.accountId) params = params.set('accountId', query.accountId);
    if (query.status) params = params.set('status', query.status);
    return this.http.get<Statement[]>(this.url('/api/statements'), { ...options, params });
  }

  /** 202 = accepted, 200 = duplicate (`duplicate: true`, existing statement returned). */
  uploadStatement(accountId: Uuid, file: File, options?: ApiCallOptions): Observable<UploadAccepted> {
    const form = new FormData();
    form.append('accountId', accountId);
    form.append('file', file, file.name);
    return this.http.post<UploadAccepted>(this.url('/api/statements'), form, options);
  }

  getStatement(statementId: Uuid, options?: ApiCallOptions): Observable<Statement> {
    return this.http.get<Statement>(this.url(`/api/statements/${enc(statementId)}`), options);
  }

  retryStatement(statementId: Uuid, options?: ApiCallOptions): Observable<Statement> {
    return this.http.post<Statement>(this.url(`/api/statements/${enc(statementId)}/retry`), null, options);
  }

  /** URL of the `streamStatementEvents` SSE endpoint (consumed with EventSource). */
  streamStatementEventsUrl(statementId: Uuid): string {
    return this.url(`/api/statements/${enc(statementId)}/events`);
  }
}

const enc = encodeURIComponent;
