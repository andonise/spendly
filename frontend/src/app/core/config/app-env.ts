import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';
import { ApiGroup, Environment } from '../../../environments/environment.model';

export type { ApiGroup, Environment };

/** The active environment; injectable so tests can override mock flags and timings. */
export const APP_ENV = new InjectionToken<Environment>('APP_ENV', {
  providedIn: 'root',
  factory: () => environment,
});
