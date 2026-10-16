/**
 * Synthetic seed data for the mock API. Everything mirrors the fictional data in
 * samples/README.md (Generic Bank / Revolut / credit card, RO**…3333 etc.). No real data.
 */
import { Account, Category, CategorySource, CounterpartyKind, QuestionKind, TransferStatus } from '../models/api.models';

export const ACCOUNT_IDS = {
  current: '0b6f1c1e-1d2a-4c55-9a51-000000000001',
  revolut: '0b6f1c1e-1d2a-4c55-9a51-000000000002',
  card: '0b6f1c1e-1d2a-4c55-9a51-000000000003',
} as const;

/** Fictional sample IBANs (samples/README.md), used only to seed the mock's duplicate check. */
export const SEED_IBANS: Record<string, string> = {
  [ACCOUNT_IDS.current]: 'RO54 SPND 0000 1111 2222 3333',
  [ACCOUNT_IDS.revolut]: 'RO32 FAKE 0000 4444 5555 6666',
  [ACCOUNT_IDS.card]: 'RO45 SPND 0000 7777 8888 9999',
};

export const SEED_ACCOUNTS: Account[] = [
  {
    id: ACCOUNT_IDS.current,
    label: 'Generic Bank current',
    bank: 'Generic Bank',
    type: 'CURRENT',
    currency: 'RON',
    ibanMasked: 'RO**…3333',
    cardLast4: '1111',
    activeFrom: '2026-01-01',
    closedAt: null,
    createdAt: '2026-09-01T08:00:00Z',
  },
  {
    id: ACCOUNT_IDS.revolut,
    label: 'Revolut',
    bank: 'Revolut',
    type: 'E_WALLET',
    currency: 'RON',
    ibanMasked: 'RO**…6666',
    cardLast4: null,
    activeFrom: '2026-01-01',
    closedAt: null,
    createdAt: '2026-09-01T08:01:00Z',
  },
  {
    id: ACCOUNT_IDS.card,
    label: 'Generic Bank credit card',
    bank: 'Generic Bank',
    type: 'CREDIT_CARD',
    currency: 'RON',
    ibanMasked: 'RO**…9999',
    cardLast4: '5556',
    activeFrom: '2026-01-01',
    closedAt: null,
    createdAt: '2026-09-01T08:02:00Z',
  },
];

/**
 * Synthetic counterparty tokens. The real backend uses an HMAC of the IBAN / person name; the mock
 * derives a stable 64-hex string from a key, so no raw value is ever involved.
 */
export function syntheticToken(key: string): string {
  let out = '';
  for (let round = 0; round < 8; round++) {
    let hash = 0x811c9dc5 ^ round;
    for (const ch of `${round}:${key}`) {
      hash ^= ch.charCodeAt(0);
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    out += hash.toString(16).padStart(8, '0');
  }
  return out;
}

export interface CounterpartySeed {
  kind: CounterpartyKind;
  label: string | null;
  isOwnAccount: boolean;
  ownAccountId: string | null;
}

/** Counterparties seen in the sample statements (fictional; samples/README.md). */
export const SEED_COUNTERPARTIES = {
  /** Third-party landlord IBAN RO38 TEST …0000 + [PERSON#3f9a1c2e]. */
  landlord: { kind: 'IBAN', label: 'Landlord', isOwnAccount: false, ownAccountId: null },
  /** The own Revolut IBAN (…6666) seen in the current account. */
  revolutIban: { kind: 'IBAN', label: null, isOwnAccount: true, ownAccountId: ACCOUNT_IDS.revolut },
  /** The own credit-card IBAN (…9999) seen in the current account. */
  cardIban: { kind: 'IBAN', label: null, isOwnAccount: true, ownAccountId: ACCOUNT_IDS.card },
  /** A person who sent money to the current account. */
  friend: { kind: 'PERSON', label: null, isOwnAccount: false, ownAccountId: null },
  /** A person paid from Revolut. */
  roommate: { kind: 'PERSON', label: null, isOwnAccount: false, ownAccountId: null },
  /** An unregistered IBAN (…7788) that topped up Revolut: an unconfirmed transfer. */
  unknownIban: { kind: 'IBAN', label: null, isOwnAccount: false, ownAccountId: null },
} as const satisfies Record<string, CounterpartySeed>;
export type CounterpartyKey = keyof typeof SEED_COUNTERPARTIES;

/** A transaction in a mock statement. `category: null` = unknown → becomes a question. */
export interface TxnTemplate {
  date: string;
  description: string;
  amount: string;
  category: Category | null;
  /** How the category was decided (default RULE; TRANSFER_MATCH for internal transfers). */
  source?: CategorySource;
  /** Classifier confidence 0..1 (default 1). */
  confidence?: number;
  /** Default: INTERNAL when category is INTERNAL_TRANSFER, else NONE. */
  transfer?: TransferStatus;
  counterparty?: CounterpartyKey;
  /** Seeded rows that share a pairKey are linked as a transfer pair. */
  pairKey?: string;
  question?: { kind: QuestionKind; text: string; suggestions: Category[]; global?: boolean };
}

/**
 * generic-bank-2026-08.csv after sanitizing and categorizing; 3 unknowns become questions.
 * Also the template for every upload. Seeded as CATEGORIZED with GENERIC_BANK_SEED_ANSWERS.
 */
export const GENERIC_BANK_AUGUST: TxnTemplate[] = [
  { date: '2026-08-01', description: 'INCASARE SALARIU ACME SOFTWARE SRL', amount: '8500.00', category: 'INCOME', source: 'RULE', confidence: 0.98 },
  { date: '2026-08-02', description: 'PLATA CARD ****1111 KAUFLAND 3412 IASI RO', amount: '-187.43', category: 'GROCERIES', source: 'RULE' },
  { date: '2026-08-03', description: 'PLATA CARD ****1111 STARBUCKS PALAS IASI', amount: '-12.50', category: 'RESTAURANTS', source: 'EMBEDDING', confidence: 0.91 },
  { date: '2026-08-03', description: 'PLATA CARD ****1111 STARBUCKS PALAS IASI', amount: '-12.50', category: 'RESTAURANTS', source: 'EMBEDDING', confidence: 0.91 },
  {
    date: '2026-08-05',
    description: 'TRANSFER CATRE [PERSON#3f9a1c2e] RO**…0000 chirie august',
    amount: '-2200.00',
    category: null,
    transfer: 'EXTERNAL',
    counterparty: 'landlord',
    question: {
      kind: 'CATEGORY',
      text: 'Is this transfer to [PERSON#3f9a1c2e] rent, a loan repayment, or something else?',
      suggestions: ['RENT_MORTGAGE', 'OTHER'],
    },
  },
  { date: '2026-08-07', description: 'PLATA FACTURA ENEL ENERGIE MUNTENIA', amount: '-245.10', category: 'UTILITIES', source: 'RULE' },
  { date: '2026-08-10', description: 'TRANSFER CATRE REVOLUT RO**…6666', amount: '-500.00', category: 'INTERNAL_TRANSFER', source: 'TRANSFER_MATCH', counterparty: 'revolutIban', pairKey: 'bt-revolut' },
  { date: '2026-08-12', description: 'PLATA CARD ****1111 NETFLIX.COM AMSTERDAM', amount: '-55.99', category: 'SUBSCRIPTIONS', source: 'RULE' },
  { date: '2026-08-14', description: 'PLATA CARD ****1111 OMV PETROM 2231 IASI', amount: '-312.75', category: 'FUEL', source: 'EMBEDDING', confidence: 0.88 },
  { date: '2026-08-15', description: 'PLATA CARD CREDIT RO**…9999', amount: '-800.00', category: 'INTERNAL_TRANSFER', source: 'TRANSFER_MATCH', counterparty: 'cardIban', pairKey: 'card-repayment' },
  { date: '2026-08-18', description: 'RETRAGERE NUMERAR ATM SPENDLY BANK IASI', amount: '-400.00', category: 'CASH_WITHDRAWAL', source: 'RULE' },
  { date: '2026-08-20', description: 'PLATA CARD ****1111 LIDL DISCOUNT IASI', amount: '-96.40', category: 'GROCERIES', source: 'RULE' },
  {
    date: '2026-08-21',
    description: 'PLATA CARD ****1111 LIBRĂRIA CĂRTUREȘTI IAȘI',
    amount: '-89.90',
    category: null,
    question: {
      kind: 'CATEGORY',
      text: 'Is LIBRĂRIA CĂRTUREȘTI a bookshop for studying, a gift shop, or entertainment?',
      suggestions: ['EDUCATION', 'SHOPPING', 'ENTERTAINMENT'],
    },
  },
  {
    date: '2026-08-22',
    description: 'Reincarcare [PHONE] Orange',
    amount: '-30.00',
    category: null,
    question: {
      kind: 'CATEGORY',
      text: 'Is this Orange top-up a phone bill or a subscription?',
      suggestions: ['UTILITIES', 'SUBSCRIPTIONS'],
    },
  },
  { date: '2026-08-25', description: 'COMISION ADMINISTRARE CONT', amount: '-15.00', category: 'BANK_FEES', source: 'RULE' },
  { date: '2026-08-28', description: 'PLATA CARD ****1111 EMAG.RO MARKETPLACE', amount: '-1249.99', category: 'SHOPPING', source: 'LLM', confidence: 0.74 },
  { date: '2026-08-30', description: 'TRANSFER DE LA [PERSON#8c41d7b0]', amount: '150.00', category: 'INCOME', source: 'LLM', confidence: 0.66, transfer: 'EXTERNAL', counterparty: 'friend' },
  { date: '2026-08-31', description: 'PLATA CARD ****1111 TRATTORIA IL CALCIO IASI', amount: '-186.00', category: 'RESTAURANTS', source: 'LLM', confidence: 0.81 },
];

/**
 * revolut-2026-08.csv: seeded as NEEDS_INPUT with 2 category questions. The 11 Aug top-up is the
 * INTERNAL pair of the −500.00 from Generic Bank. One extra synthetic top-up from an unregistered
 * IBAN has no pair: it stays CANDIDATE and raises the (statement-independent) TRANSFER question.
 */
export const REVOLUT_AUGUST: TxnTemplate[] = [
  {
    date: '2026-08-11',
    description: 'Top-Up by *3333',
    amount: '500.00',
    category: 'INTERNAL_TRANSFER',
    source: 'TRANSFER_MATCH',
    pairKey: 'bt-revolut',
  },
  { date: '2026-08-13', description: 'Kaufland', amount: '-120.00', category: 'GROCERIES', source: 'RULE' },
  { date: '2026-08-15', description: 'Spotify', amount: '-26.99', category: 'SUBSCRIPTIONS', source: 'RULE' },
  {
    date: '2026-08-17',
    description: 'Bolt',
    amount: '-18.40',
    category: null,
    question: {
      kind: 'CATEGORY',
      text: 'Was this Bolt payment a ride or a food delivery?',
      suggestions: ['TRANSPORT', 'RESTAURANTS'],
    },
  },
  {
    date: '2026-08-18',
    description: 'To [PERSON#5e1d0c7a]',
    amount: '-100.00',
    category: null,
    transfer: 'EXTERNAL',
    counterparty: 'roommate',
    question: {
      kind: 'CATEGORY',
      text: 'Is this payment to [PERSON#5e1d0c7a] rent, a gift, or a shared expense?',
      suggestions: ['RENT_MORTGAGE', 'OTHER'],
    },
  },
  { date: '2026-08-21', description: 'Cash at Revolut ATM', amount: '-200.00', category: 'CASH_WITHDRAWAL', source: 'RULE' },
  { date: '2026-08-21', description: 'FEE Cash at Revolut ATM', amount: '-5.00', category: 'BANK_FEES', source: 'RULE' },
  {
    date: '2026-08-24',
    description: 'Top-Up from RO**…7788',
    amount: '300.00',
    category: null,
    transfer: 'CANDIDATE',
    counterparty: 'unknownIban',
    question: {
      kind: 'TRANSFER',
      global: true,
      text: 'Is this top-up from RO**…7788 a transfer from one of your accounts?',
      suggestions: ['INTERNAL_TRANSFER', 'INCOME', 'OTHER'],
    },
  },
  { date: '2026-08-28', description: 'Mega Image', amount: '-64.15', category: 'GROCERIES', source: 'EMBEDDING', confidence: 0.93 },
];

/** credit-card-2026-08.pdf: seeded as CATEGORIZED; the 800.00 repayment pairs with the current account. */
export const CREDIT_CARD_AUGUST: TxnTemplate[] = [
  { date: '2026-08-04', description: 'ALTEX IASI', amount: '-649.00', category: 'SHOPPING', source: 'RULE' },
  { date: '2026-08-09', description: 'DECATHLON IASI', amount: '-151.00', category: 'SPORT', source: 'EMBEDDING', confidence: 0.86 },
  { date: '2026-08-16', description: 'RAMBURSARE CARD', amount: '800.00', category: 'INTERNAL_TRANSFER', source: 'TRANSFER_MATCH', pairKey: 'card-repayment' },
  { date: '2026-08-25', description: 'DOBANDA CARD CREDIT', amount: '-146.00', category: 'CARD_INTEREST', source: 'RULE' },
  { date: '2026-08-31', description: 'COMISION ANUAL CARD', amount: '-5.00', category: 'BANK_FEES', source: 'RULE' },
];

/** Answers applied to the seeded Generic Bank statement (by description) so it is CATEGORIZED. */
export const GENERIC_BANK_SEED_ANSWERS: Record<string, Category> = {
  'TRANSFER CATRE [PERSON#3f9a1c2e] RO**…0000 chirie august': 'RENT_MORTGAGE',
  'PLATA CARD ****1111 LIBRĂRIA CĂRTUREȘTI IAȘI': 'EDUCATION',
  'Reincarcare [PHONE] Orange': 'UTILITIES',
};

/** Seeded statement ids. */
export const STATEMENT_IDS = {
  current: '5a7c2d10-8e3b-4f6a-b1c2-0000000000a3',
  revolut: '5a7c2d10-8e3b-4f6a-b1c2-0000000000b2',
  card: '5a7c2d10-8e3b-4f6a-b1c2-0000000000c1',
} as const;
