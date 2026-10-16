import { FormControl } from '@angular/forms';
import { ibanProblem, ibanValidator, isValidIban, maskIban, normalizeIban } from './iban';

describe('IBAN validation', () => {
  // Fictional IBANs from samples/README.md (valid mod-97 check digits).
  const valid = [
    'RO54 SPND 0000 1111 2222 3333',
    'RO32 FAKE 0000 4444 5555 6666',
    'RO45 SPND 0000 7777 8888 9999',
    'RO38TEST7777888899990000',
    'ro54 spnd 0000 1111 2222 3333',
    'GB82 WEST 1234 5698 7654 32',
  ];

  it.each(valid)('accepts %s', (iban) => {
    expect(isValidIban(iban)).toBe(true);
  });

  it('rejects wrong check digits', () => {
    expect(ibanProblem('RO55 SPND 0000 1111 2222 3333')).toBe('checksum');
  });

  it('rejects a single changed digit (typo)', () => {
    expect(ibanProblem('RO54 SPND 0000 1111 2222 3334')).toBe('checksum');
  });

  it('rejects a wrong length for the country', () => {
    expect(ibanProblem('RO54 SPND 0000 1111 2222 333')).toBe('length');
  });

  it('rejects malformed input', () => {
    expect(ibanProblem('hello')).toBe('format');
    expect(ibanProblem('1234 5678 9012 3456')).toBe('format');
    expect(ibanProblem('RO54-SPND-0000')).toBe('format');
  });

  it('normalizes and masks like the backend (country + **… + last 4)', () => {
    expect(normalizeIban(' ro54 spnd 0000 1111 2222 3333 ')).toBe('RO54SPND0000111122223333');
    expect(maskIban('RO54 SPND 0000 1111 2222 3333')).toBe('RO**…3333');
  });

  it('works as a reactive-forms validator', () => {
    expect(new FormControl('RO54 SPND 0000 1111 2222 3333', ibanValidator).errors).toBeNull();
    expect(new FormControl('', ibanValidator).errors).toBeNull(); // `required` handles empty
    expect(new FormControl('RO55 SPND 0000 1111 2222 3333', ibanValidator).errors).toEqual({ iban: 'checksum' });
  });
});
