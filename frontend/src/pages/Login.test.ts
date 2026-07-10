import { describe, expect, it } from 'vitest';
import { loginErrorMessage } from './Login';

/**
 * Regression coverage for the "second device login fails" investigation: the frontend
 * was collapsing every rejected login request — network errors, timeouts, 500s — into
 * "Invalid email or password", which made backend outages (Render cold-start/OOM) look
 * like a credentials problem. These pin the three branches down.
 */
describe('loginErrorMessage', () => {
  it('shows a connectivity message when there is no response (network error/timeout)', () => {
    expect(loginErrorMessage({})).toBe(
      "Can't reach the server. Check your connection and try again.",
    );
  });

  it('shows the server message for a real 401', () => {
    const err = { response: { status: 401, data: { message: 'Invalid credentials' } } };
    expect(loginErrorMessage(err)).toBe('Invalid credentials');
  });

  it('falls back to the generic invalid-credentials copy on a 401 with no message', () => {
    const err = { response: { status: 401, data: {} } };
    expect(loginErrorMessage(err)).toBe('Invalid email or password');
  });

  it('shows a generic server-error message for any 5xx, never the credentials message', () => {
    const err = { response: { status: 500, data: { message: 'db unreachable' } } };
    expect(loginErrorMessage(err)).toBe('Something went wrong on our end. Please try again.');
  });

  it('shows a generic server-error message for a 503 too', () => {
    const err = { response: { status: 503, data: {} } };
    expect(loginErrorMessage(err)).toBe('Something went wrong on our end. Please try again.');
  });
});
