import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Account, AccountCreate, AccountUpdate, Uuid } from '../models/api.models';
import { ApiCallOptions, injectApiUrl } from './api-options';

/** OpenAPI tag `accounts`. Method names = operationIds. */
@Injectable({ providedIn: 'root' })
export class AccountsApi {
  private readonly http = inject(HttpClient);
  private readonly url = injectApiUrl();

  listAccounts(options?: ApiCallOptions): Observable<Account[]> {
    return this.http.get<Account[]>(this.url('/api/accounts'), options);
  }

  createAccount(body: AccountCreate, options?: ApiCallOptions): Observable<Account> {
    return this.http.post<Account>(this.url('/api/accounts'), body, options);
  }

  getAccount(accountId: Uuid, options?: ApiCallOptions): Observable<Account> {
    return this.http.get<Account>(this.url(`/api/accounts/${enc(accountId)}`), options);
  }

  updateAccount(accountId: Uuid, body: AccountUpdate, options?: ApiCallOptions): Observable<Account> {
    return this.http.patch<Account>(this.url(`/api/accounts/${enc(accountId)}`), body, options);
  }

  deleteAccount(accountId: Uuid, options?: ApiCallOptions): Observable<void> {
    return this.http.delete<void>(this.url(`/api/accounts/${enc(accountId)}`), options);
  }
}

const enc = encodeURIComponent;
