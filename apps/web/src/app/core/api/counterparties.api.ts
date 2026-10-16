import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Counterparty, CounterpartyUpdate } from '../models/api.models';
import { ApiCallOptions, injectApiUrl } from './api-options';

/** OpenAPI tag `counterparties`. Method names = operationIds. */
@Injectable({ providedIn: 'root' })
export class CounterpartiesApi {
  private readonly http = inject(HttpClient);
  private readonly url = injectApiUrl();

  /** Most recently seen first. */
  listCounterparties(options?: ApiCallOptions): Observable<Counterparty[]> {
    return this.http.get<Counterparty[]>(this.url('/api/counterparties'), options);
  }

  /** Label a counterparty and/or mark it as one of your own accounts (re-runs transfer detection). */
  updateCounterparty(token: string, body: CounterpartyUpdate, options?: ApiCallOptions): Observable<Counterparty> {
    return this.http.put<Counterparty>(this.url(`/api/counterparties/${enc(token)}`), body, options);
  }
}

const enc = encodeURIComponent;
