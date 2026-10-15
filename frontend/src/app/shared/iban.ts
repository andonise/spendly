import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** IBAN lengths for countries the app is likely to see; others only need 15–34 characters. */
const IBAN_LENGTHS: Record<string, number> = {
  RO: 24, AT: 20, BE: 16, BG: 22, CH: 21, CZ: 24, DE: 22, DK: 18, ES: 24, FI: 18, FR: 27,
  GB: 22, GR: 27, HU: 28, IE: 22, IT: 27, LT: 20, LU: 20, MD: 24, NL: 18, PL: 28, PT: 25, SE: 24,
};

/** Removes spaces and upper-cases: "ro54 spnd 0000 …" → "RO54SPND0000…". */
export function normalizeIban(value: string): string {
  return value.replace(/\s+/g, '').toUpperCase();
}

export type IbanProblem = 'format' | 'length' | 'checksum';

/** Returns null when valid, otherwise the first problem found (format → length → mod-97). */
export function ibanProblem(value: string): IbanProblem | null {
  const iban = normalizeIban(value);
  if (!/^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$/.test(iban)) return 'format';
  const expected = IBAN_LENGTHS[iban.slice(0, 2)];
  if (expected !== undefined && iban.length !== expected) return 'length';
  return ibanMod97(iban) === 1 ? null : 'checksum';
}

export function isValidIban(value: string): boolean {
  return ibanProblem(value) === null;
}

/** ISO 13616 mod-97 over the rearranged IBAN (letters A=10 … Z=35), computed piecewise. */
export function ibanMod97(normalizedIban: string): number {
  const rearranged = normalizedIban.slice(4) + normalizedIban.slice(0, 4);
  let remainder = 0;
  for (const ch of rearranged) {
    const code = ch.charCodeAt(0);
    const digits = code >= 65 && code <= 90 ? String(code - 55) : ch;
    for (const d of digits) remainder = (remainder * 10 + Number(d)) % 97;
  }
  return remainder;
}

/** "RO54SPND00001111…3333" → "RO**…3333" (same format as the backend's `ibanMasked`). */
export function maskIban(value: string): string {
  const iban = normalizeIban(value);
  return `${iban.slice(0, 2)}**…${iban.slice(-4)}`;
}

/** Reactive-forms validator: `{ iban: 'format' | 'length' | 'checksum' }` when invalid. */
export const ibanValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = control.value as string | null | undefined;
  if (!value) return null; // `required` handles empty values
  const problem = ibanProblem(value);
  return problem ? { iban: problem } : null;
};
