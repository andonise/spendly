import { CategorySource, CounterpartyRef, TransferStatus } from '../core/api';

export const CATEGORY_SOURCE_LABELS: Record<CategorySource, string> = {
  RULE: 'Rule',
  EMBEDDING: 'Similar transactions',
  LLM: 'AI',
  USER: 'You',
  TRANSFER_MATCH: 'Transfer match',
};

export const TRANSFER_STATUS_LABELS: Record<TransferStatus, string> = {
  NONE: 'Not a transfer',
  CANDIDATE: 'Unconfirmed transfer',
  INTERNAL: 'Internal transfer',
  EXTERNAL: 'To / from others',
};

/** "Source: AI · 74% confident" for the category chip tooltip. */
export function categoryProvenance(source: CategorySource | null | undefined, confidence: number | null | undefined): string {
  const parts = [`Source: ${source ? CATEGORY_SOURCE_LABELS[source] : 'unknown'}`];
  if (confidence !== null && confidence !== undefined) parts.push(`${Math.round(confidence * 100)}% confident`);
  return parts.join(' · ');
}

/**
 * Display-only short form of a counterparty token. Tokens are HMACs; still, never show or log
 * more than this prefix.
 */
export function shortToken(token: string): string {
  return `${token.slice(0, 6)}…`;
}

/** The label to show for a counterparty: its user label, else kind + short token. */
export function counterpartyName(cp: CounterpartyRef, ownAccountLabel?: string | null): string {
  if (cp.label) return cp.label;
  if (ownAccountLabel) return ownAccountLabel;
  return `${cp.kind === 'PERSON' ? 'Person' : 'IBAN'} ${shortToken(cp.token)}`;
}
