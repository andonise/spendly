import { DateRange, Granularity, IsoDate } from '../../core/api';
import { addDays, addMonths, daysBetween, endOfMonth, weekday } from '../../shared/dates';

export function today(): IsoDate {
  const d = new Date();
  return new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate())).toISOString().slice(0, 10);
}

/** The DAY / WEEK / MONTH period containing [anchor]; CUSTOM keeps [from]..[to]. */
export function periodFor(granularity: Granularity, anchor: IsoDate, to?: IsoDate): DateRange {
  switch (granularity) {
    case 'DAY':
      return { from: anchor, to: anchor };
    case 'WEEK': {
      const from = addDays(anchor, 1 - weekday(anchor));
      return { from, to: addDays(from, 6) };
    }
    case 'MONTH': {
      const from = anchor.slice(0, 8) + '01';
      return { from, to: endOfMonth(from) };
    }
    default:
      return to && to >= anchor ? { from: anchor, to } : { from: anchor, to: addDays(anchor, 29) };
  }
}

/** The previous (-1) or next (+1) period of the same kind and length. */
export function shift(granularity: Granularity, range: DateRange, step: -1 | 1): DateRange {
  switch (granularity) {
    case 'DAY':
      return periodFor('DAY', addDays(range.from, step));
    case 'WEEK':
      return periodFor('WEEK', addDays(range.from, 7 * step));
    case 'MONTH':
      return periodFor('MONTH', addMonths(range.from, step));
    default: {
      const days = daysBetween(range.from, range.to) * step;
      return { from: addDays(range.from, days), to: addDays(range.to, days) };
    }
  }
}

const fmt = (options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', ...options });
const dayMonthYear = fmt({ day: 'numeric', month: 'short', year: 'numeric' });
const dayMonth = fmt({ day: 'numeric', month: 'short' });
const monthYear = fmt({ month: 'long', year: 'numeric' });
const shortMonth = fmt({ month: 'short' });

const date = (d: IsoDate) => new Date(d + 'T00:00:00Z');

/** "August 2026", "10 – 16 Aug 2026", "12 Aug 2026". */
export function periodLabel(granularity: Granularity, range: DateRange): string {
  if (granularity === 'MONTH') return monthYear.format(date(range.from));
  if (range.from === range.to) return dayMonthYear.format(date(range.from));
  return rangeLabel(range);
}

/** "1 – 14 Aug 2026", or with both months/years when they differ. */
export function rangeLabel(range: DateRange): string {
  if (range.from === range.to) return dayMonthYear.format(date(range.from));
  const sameYear = range.from.slice(0, 4) === range.to.slice(0, 4);
  const sameMonth = sameYear && range.from.slice(0, 7) === range.to.slice(0, 7);
  const start = sameMonth ? String(Number(range.from.slice(8))) : sameYear ? dayMonth.format(date(range.from)) : dayMonthYear.format(date(range.from));
  return `${start} – ${dayMonthYear.format(date(range.to))}`;
}

export function shortMonthLabel(d: IsoDate): string {
  return shortMonth.format(date(d));
}

export function shortDayLabel(d: IsoDate): string {
  return dayMonth.format(date(d));
}
