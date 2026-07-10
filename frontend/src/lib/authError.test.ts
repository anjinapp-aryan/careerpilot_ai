import { describe, expect, it } from 'vitest';
import { authErrorMessage } from './authError';

/**
 * Regression coverage for the "second device login fails" investigation: the frontend
 * was collapsing every rejected auth request — network errors, timeouts, 500s — into a
 * single hardcoded string, which made backend outages (Render cold-start/OOM) look like
 * a credentials/registration problem. Shared by Login.tsx and Register.tsx.
 */
describe('authErrorMessage', () => {
  it('shows a connectivity message when there is no response (network error/timeout)', () => {
    expect(authErrorMessage({}, 'Invalid email or password')).toBe(
      "Can't reach the server. Check your connection and try again.",
    );
  });

  it('shows the server message for a real 401', () => {
    const err = { response: { status: 401, data: { message: 'Invalid credentials' } } };
    expect(authErrorMessage(err, 'Invalid email or password')).toBe('Invalid credentials');
  });

  it('falls back to the caller-supplied message on a 4xx with no server message', () => {
    const err = { response: { status: 401, data: {} } };
    expect(authErrorMessage(err, 'Invalid email or password')).toBe('Invalid email or password');
  });

  it('shows the server message for a 409 conflict (e.g. duplicate email on register)', () => {
    const err = { response: { status: 409, data: { message: 'Email already registered' } } };
    expect(authErrorMessage(err, 'Registration failed')).toBe('Email already registered');
  });

  it('shows a generic server-error message for any 5xx, never the caller-supplied fallback', () => {
    const err = { response: { status: 500, data: { message: 'db unreachable' } } };
    expect(authErrorMessage(err, 'Registration failed')).toBe(
      'Something went wrong on our end. Please try again.',
    );
  });

  it('shows a generic server-error message for a 503 too', () => {
    const err = { response: { status: 503, data: {} } };
    expect(authErrorMessage(err, 'Invalid email or password')).toBe(
      'Something went wrong on our end. Please try again.',
    );
  });
});
