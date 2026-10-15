import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { provideMockApiForTests } from './core/mock/testing';

describe('App shell', () => {
  it('renders the nav and the open-questions badge from GET /api/questions?status=OPEN', async () => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), ...provideMockApiForTests()],
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');

    for (const label of ['Dashboard', 'Accounts', 'Upload', 'Statements', 'Transactions', 'Learning', 'Chat']) {
      expect(text).toContain(label);
    }
    // Seed data: 2 category questions on the Revolut statement + 1 transfer question.
    expect(text).toContain('3 questions need your input');
    expect(text).toContain('Mock API');
  });
});
