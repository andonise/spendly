import { Routes } from '@angular/router';

/** Every feature is lazy-loaded. */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'statements' },
  {
    path: 'dashboard',
    title: 'Dashboard · Spendly',
    loadComponent: () => import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
  },
  {
    path: 'accounts',
    title: 'Accounts · Spendly',
    loadComponent: () => import('./features/accounts/accounts-page').then((m) => m.AccountsPage),
  },
  {
    path: 'upload',
    title: 'Upload · Spendly',
    loadComponent: () => import('./features/upload/upload-page').then((m) => m.UploadPage),
  },
  {
    path: 'statements',
    title: 'Statements · Spendly',
    loadComponent: () => import('./features/statement/statements-page').then((m) => m.StatementsPage),
  },
  {
    path: 'statements/:id',
    title: 'Statement · Spendly',
    loadComponent: () => import('./features/statement/statement-page').then((m) => m.StatementPage),
  },
  {
    path: 'transactions',
    title: 'Transactions · Spendly',
    loadComponent: () => import('./features/transactions/transactions-page').then((m) => m.TransactionsPage),
  },
  {
    path: 'learning',
    title: 'Learning · Spendly',
    loadComponent: () => import('./features/learning/learning-page').then((m) => m.LearningPage),
  },
  {
    path: 'chat',
    title: 'Chat · Spendly',
    loadComponent: () => import('./features/chat/chat-page').then((m) => m.ChatPage),
  },
  {
    path: '**',
    title: 'Not found · Spendly',
    loadComponent: () => import('./features/not-found-page').then((m) => m.NotFoundPage),
  },
];
