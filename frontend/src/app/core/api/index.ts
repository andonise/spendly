/**
 * The API seam. Features import API services and models only from here
 * (`core/api`), never from HttpClient directly, so this folder can be replaced by an
 * openapi-generator client later (see README).
 */
export * from '../models/api.models';
export * from './api-options';
export * from './accounts.api';
export * from './statements.api';
export * from './questions.api';
