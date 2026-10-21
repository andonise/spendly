import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Coverage, IsoDate, Report, ReportQuery, Uuid } from '../models/api.models';
import { ApiCallOptions, injectApiUrl } from './api-options';

/** OpenAPI tag `reports`. Method names = operationIds. */
@Injectable({ providedIn: 'root' })
export class ReportsApi {
  private readonly http = inject(HttpClient);
  private readonly url = injectApiUrl();

  /** Cached, stale-while-rebuilding, or built during the request. Poll `getReportById` while `stale`. */
  getReport(query: ReportQuery, options?: ApiCallOptions): Observable<Report> {
    let params = new HttpParams().set('granularity', query.granularity).set('from', query.from);
    if (query.to) params = params.set('to', query.to);
    for (const id of query.accountIds ?? []) params = params.append('accountId', id);
    return this.http.get<Report>(this.url('/api/reports'), { ...options, params });
  }

  getReportById(reportId: Uuid, options?: ApiCallOptions): Observable<Report> {
    return this.http.get<Report>(this.url(`/api/reports/${encodeURIComponent(reportId)}`), options);
  }

  getCoverage(from: IsoDate, to: IsoDate, accountIds: Uuid[] = [], options?: ApiCallOptions): Observable<Coverage> {
    let params = new HttpParams().set('from', from).set('to', to);
    for (const id of accountIds) params = params.append('accountId', id);
    return this.http.get<Coverage>(this.url('/api/coverage'), { ...options, params });
  }
}
