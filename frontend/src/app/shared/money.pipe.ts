import { Pipe, PipeTransform } from '@angular/core';

const formatters = new Map<string, Intl.NumberFormat>();

function formatter(currency: string): Intl.NumberFormat {
  let f = formatters.get(currency);
  if (!f) {
    f = new Intl.NumberFormat('ro-RO', { style: 'currency', currency });
    formatters.set(currency, f);
  }
  return f;
}

/**
 * Formats money for display: `'-1249.99' | money` → "-1.249,99 RON".
 * Accepts the API's decimal strings or numbers; currency defaults to RON.
 * Invalid input renders as an empty string.
 */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(value: string | number | null | undefined, currency = 'RON'): string {
    return formatMoney(value, currency);
  }
}

export function formatMoney(value: string | number | null | undefined, currency = 'RON'): string {
  if (value === null || value === undefined || value === '') return '';
  const amount = typeof value === 'number' ? value : Number(value.trim());
  if (!Number.isFinite(amount)) return '';
  return formatter(currency || 'RON').format(amount);
}
