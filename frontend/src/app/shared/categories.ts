import { CATEGORIES, Category } from '../core/api';

export const CATEGORY_LABELS: Record<Category, string> = {
  GROCERIES: 'Groceries',
  RESTAURANTS: 'Restaurants & cafés',
  FUEL: 'Fuel',
  TRANSPORT: 'Transport',
  UTILITIES: 'Utilities',
  RENT_MORTGAGE: 'Rent & mortgage',
  SUBSCRIPTIONS: 'Subscriptions',
  SHOPPING: 'Shopping',
  HEALTH: 'Health',
  SPORT: 'Sport',
  ENTERTAINMENT: 'Entertainment',
  TRAVEL: 'Travel',
  EDUCATION: 'Education',
  PETS: 'Pets',
  CASH_WITHDRAWAL: 'Cash withdrawal',
  BANK_FEES: 'Bank fees',
  CARD_INTEREST: 'Card interest',
  FX_FEES: 'FX fees',
  INSURANCE: 'Insurance',
  TAXES: 'Taxes',
  INCOME: 'Income',
  INTERNAL_TRANSFER: 'Internal transfer',
  OTHER: 'Other',
};

export const CATEGORY_OPTIONS = CATEGORIES.map((value) => ({ value, label: CATEGORY_LABELS[value] }));

export function categoryLabel(category: Category | null | undefined): string {
  return category ? (CATEGORY_LABELS[category] ?? category) : 'Uncategorized';
}
