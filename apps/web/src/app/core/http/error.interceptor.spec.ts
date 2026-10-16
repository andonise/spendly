import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { handledErrors } from '../api/api-options';
import { errorInterceptor } from './error.interceptor';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let open: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    open = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open } },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('shows the RFC 7807 title and detail in a snackbar and rethrows', async () => {
    const result = firstValueFrom(http.delete('/api/accounts/1'));
    httpMock.expectOne('/api/accounts/1').flush(
      { type: 'about:blank', title: 'Conflict', status: 409, detail: 'The account still has 2 statements.' },
      { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await expect(result).rejects.toMatchObject({ status: 409 });
    expect(open).toHaveBeenCalledTimes(1);
    expect(open.mock.calls[0][0]).toBe('Conflict: The account still has 2 statements.');
  });

  it('lists field errors when there is no detail', async () => {
    const result = firstValueFrom(http.post('/api/accounts', {}));
    httpMock.expectOne('/api/accounts').flush(
      { title: 'Bad Request', status: 400, errors: [{ field: 'iban', message: 'is not a valid IBAN' }] },
      { status: 400, statusText: 'Bad Request' },
    );
    await expect(result).rejects.toBeTruthy();
    expect(open.mock.calls[0][0]).toBe('Bad Request: iban is not a valid IBAN');
  });

  it('parses a problem sent as a JSON string', async () => {
    const result = firstValueFrom(http.get('/api/statements/x'));
    httpMock.expectOne('/api/statements/x').flush(
      JSON.stringify({ title: 'Not Found', detail: 'Statement x does not exist.' }),
      { status: 404, statusText: 'Not Found' },
    );
    await expect(result).rejects.toBeTruthy();
    expect(open.mock.calls[0][0]).toBe('Not Found: Statement x does not exist.');
  });

  it('falls back to the status text for non-problem errors', async () => {
    const result = firstValueFrom(http.get('/api/accounts'));
    httpMock.expectOne('/api/accounts').flush(null, { status: 500, statusText: 'Internal Server Error' });
    await expect(result).rejects.toBeTruthy();
    expect(open.mock.calls[0][0]).toBe('Internal Server Error');
  });

  it('stays silent for statuses the caller handles (e.g. 409 on answers)', async () => {
    const result = firstValueFrom(http.post('/api/statements/x/answers', {}, { context: handledErrors(409) }));
    httpMock.expectOne('/api/statements/x/answers').flush(
      { title: 'Conflict', status: 409 },
      { status: 409, statusText: 'Conflict' },
    );
    await expect(result).rejects.toMatchObject({ status: 409 });
    expect(open).not.toHaveBeenCalled();
  });

  it('does nothing on success', async () => {
    const result = firstValueFrom(http.get('/api/accounts'));
    httpMock.expectOne('/api/accounts').flush([]);
    await expect(result).resolves.toEqual([]);
    expect(open).not.toHaveBeenCalled();
  });
});
