import type { Page } from '@playwright/test';

/**
 * Seeds a fake-but-shaped zustand-persist auth session directly into localStorage, mirroring
 * `useAuthStore`'s own `persist({ name: 'careerpilot-auth', partialize: token+user })` shape
 * exactly (see `src/lib/auth.ts`). Deliberately does NOT drive the real `/api/auth/login` flow —
 * this suite tests Guided Apply UI/API-contract behavior, not the login page, and a real login
 * would require a real backend + Neon-backed user this E2E suite has no access to. `addInitScript`
 * runs before any page script, so `useAuthStore`'s persist rehydration sees it on first paint.
 */
export async function loginAs(page: Page) {
  const user = {
    userId: '11111111-1111-1111-1111-111111111111',
    orgId: '22222222-2222-2222-2222-222222222222',
    email: 'e2e-user@example.test',
    role: 'CANDIDATE',
    fullName: 'E2E Test User',
  };
  await page.addInitScript(
    ([storedUser]) => {
      window.localStorage.setItem(
        'careerpilot-auth',
        JSON.stringify({ state: { token: 'e2e-fake-jwt-token', user: storedUser }, version: 0 }),
      );
    },
    [user],
  );
  return user;
}
