/**
 * Distinguishes network/timeout failures, server-explained 4xx errors, and 5xx failures
 * for auth forms (login, register) instead of collapsing every rejected request into one
 * hardcoded string. A backend outage/cold-start (no `.response` at all, or a 5xx) should
 * never be reported as an invalid-credentials/registration-failed error — those are only
 * accurate when the server actually rejected the submitted data.
 */
export function authErrorMessage(e: any, fallback: string): string {
  const status = e?.response?.status;
  if (status === undefined) {
    return "Can't reach the server. Check your connection and try again.";
  }
  if (status >= 500) {
    return 'Something went wrong on our end. Please try again.';
  }
  return e?.response?.data?.message || fallback;
}
