import { MoneyPipe } from './money.pipe';

/** Intl uses non-breaking spaces; normalize them for readable assertions. */
const plain = (s: string) => s.replace(/\s/g, ' ').replace('−', '-');

describe('MoneyPipe', () => {
  const pipe = new MoneyPipe();

  it('formats decimal strings as RON in ro-RO style', () => {
    expect(plain(pipe.transform('1249.99'))).toBe('1.249,99 RON');
  });

  it('formats negative string amounts (money out)', () => {
    expect(plain(pipe.transform('-123.45'))).toBe('-123,45 RON');
    expect(plain(pipe.transform('-1249.99'))).toBe('-1.249,99 RON');
  });

  it('accepts numbers, including negative ones', () => {
    expect(plain(pipe.transform(8650))).toBe('8.650,00 RON');
    expect(plain(pipe.transform(-0.5))).toBe('-0,50 RON');
  });

  it('keeps 2 fraction digits for zero', () => {
    expect(plain(pipe.transform('0.00'))).toBe('0,00 RON');
  });

  it('supports another currency argument', () => {
    expect(plain(pipe.transform('-12.50', 'EUR'))).toBe('-12,50 EUR');
  });

  it('returns an empty string for null, empty and invalid input', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform('')).toBe('');
    expect(pipe.transform('abc')).toBe('');
  });
});
