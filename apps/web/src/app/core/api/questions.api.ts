import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Answer, AnswersSubmission, Question, QuestionStatus, Statement, Uuid } from '../models/api.models';
import { ApiCallOptions, injectApiUrl } from './api-options';

/** OpenAPI tag `questions`. Method names = operationIds. */
@Injectable({ providedIn: 'root' })
export class QuestionsApi {
  private readonly http = inject(HttpClient);
  private readonly url = injectApiUrl();

  getStatementQuestions(statementId: Uuid, options?: ApiCallOptions): Observable<Question[]> {
    return this.http.get<Question[]>(this.url(`/api/statements/${enc(statementId)}/questions`), options);
  }

  /** 202 → statement now CATEGORIZING; 409 → not in NEEDS_INPUT (e.g. already submitted). */
  submitAnswers(statementId: Uuid, body: AnswersSubmission, options?: ApiCallOptions): Observable<Statement> {
    return this.http.post<Statement>(this.url(`/api/statements/${enc(statementId)}/answers`), body, options);
  }

  listOpenQuestions(query: { status?: QuestionStatus } = {}, options?: ApiCallOptions): Observable<Question[]> {
    let params = new HttpParams();
    if (query.status) params = params.set('status', query.status);
    return this.http.get<Question[]>(this.url('/api/questions'), { ...options, params });
  }

  answerQuestion(questionId: Uuid, body: Answer, options?: ApiCallOptions): Observable<Question> {
    return this.http.post<Question>(this.url(`/api/questions/${enc(questionId)}/answer`), body, options);
  }
}

const enc = encodeURIComponent;
