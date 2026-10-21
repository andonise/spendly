import { IsoDate } from '../core/api';

/** Calendar arithmetic on ISO dates (`yyyy-MM-dd`), in UTC so time zones never shift a day. */

const DAY_MS = 86_400_000;

export function addDays(date: IsoDate, days: number): IsoDate {
  return new Date(Date.parse(date + 'T00:00:00Z') + days * DAY_MS).toISOString().slice(0, 10);
}

/** Inclusive number of days from [from] to [to]. */
export function daysBetween(from: IsoDate, to: IsoDate): number {
  return Math.round((Date.parse(to + 'T00:00:00Z') - Date.parse(from + 'T00:00:00Z')) / DAY_MS) + 1;
}

export function endOfMonth(date: IsoDate): IsoDate {
  const [y, m] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10);
}

/** [firstOfMonth] moved by [months], still the 1st. */
export function addMonths(firstOfMonth: IsoDate, months: number): IsoDate {
  const [y, m] = firstOfMonth.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1 + months, 1)).toISOString().slice(0, 10);
}

/** ISO weekday, 1 = Monday. */
export function weekday(date: IsoDate): number {
  return new Date(date + 'T00:00:00Z').getUTCDay() || 7;
}
