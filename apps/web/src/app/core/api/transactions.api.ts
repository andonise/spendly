import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CategoryUpdate, Transaction, TransactionPage, TransactionQuery, Uuid } from '../models/api.models';
import { ApiCallOptions, injectApiUrl } from './api-options';

/** OpenAPI tag `transactions`. Method names = operationIds. */
@Injectable({ providedIn: 'root' })
export class TransactionsApi {
  private readonly http = inject(HttpClient);
  private readonly url = injectApiUrl();

  /** Server-side filtering, sorting and paging (`TransactionPage`). */
  listTransactions(query: TransactionQuery = {}, options?: ApiCallOptions): Observable<TransactionPage> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') params = params.set(key, String(value));
    }
    return this.http.get<TransactionPage>(this.url('/api/transactions'), { ...options, params });
  }

  getTransaction(transactionId: Uuid, options?: ApiCallOptions): Observable<Transaction> {
    return this.http.get<Transaction>(this.url(`/api/transactions/${enc(transactionId)}`), options);
  }

  /** Manual correction; 409 STATEMENT_NOT_CATEGORIZED / TRANSACTION_IS_TRANSFER. */
  updateTransactionCategory(transactionId: Uuid, update: CategoryUpdate, options?: ApiCallOptions): Observable<Transaction> {
    return this.http.patch<Transaction>(this.url(`/api/transactions/${enc(transactionId)}/category`), update, options);
  }
}

const enc = encodeURIComponent;
