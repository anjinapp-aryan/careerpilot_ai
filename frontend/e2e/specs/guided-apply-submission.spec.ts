import { test, expect } from '@playwright/test';
import { loginAs } from '../fixtures/auth';
import { mockApiCatchAll, baseCard, submissionSession } from '../fixtures/mocks';

/**
 * Guided Apply — manual-submission reporting flow, on the "My submissions" panel
 * (`MySubmissionsPanel.tsx`), reached from `pages/Applications.tsx`. Covers the confirmation
 * dialog, the `USER_REPORTED_SUBMITTED` truth-model wording, double-click/race protection, and
 * the 409/500 error paths — all against a mocked `POST /api/application-submission/{id}/report-submitted`.
 */

test.beforeEach(async ({ page }) => {
  await loginAs(page);
  await mockApiCatchAll(page);
});

async function openWaitingRow(page: import('@playwright/test').Page, session: ReturnType<typeof submissionSession>) {
  // A non-empty cards list is required — `Applications.tsx` early-returns an EmptyState (and never
  // mounts `MySubmissionsPanel`) when `cards.length === 0`. This card is unrelated to the session.
  await page.route('**/api/applications/cards', (route) => route.fulfill({ json: [baseCard({ guidedApplyRequired: false, blockerReason: null, blockerDetail: null, automationHealth: null })] }));
  await page.route('**/api/application-submission', (route) =>
    route.request().method() === 'GET' ? route.fulfill({ json: [session] }) : route.continue(),
  );
  await page.goto('/applications');
  await page.getByText(`Job #${session.jobId.slice(0, 8)}`).click();
}

// 7. Manual submission dialog opens on "Mark as submitted" and shows the honest non-verification copy.
test('mark-as-submitted opens a confirmation dialog with honest non-verification wording', async ({ page }) => {
  const session = submissionSession();
  await openWaitingRow(page, session);
  await page.getByRole('button', { name: 'Mark as submitted' }).click();
  await expect(page.getByText('Did you submit this application?')).toBeVisible();
  await expect(page.getByText(/CareerPilot cannot verify an employer's site directly/)).toBeVisible();
});

test('"Not yet" closes the dialog without calling report-submitted', async ({ page }) => {
  const session = submissionSession();
  let called = false;
  await openWaitingRow(page, session);
  await page.route('**/report-submitted', async (route) => {
    called = true;
    await route.fulfill({ json: { ...session, status: 'USER_REPORTED_SUBMITTED' } });
  });
  await page.getByRole('button', { name: 'Mark as submitted' }).click();
  await page.getByRole('button', { name: 'Not yet' }).click();
  await expect(page.getByText('Did you submit this application?')).toHaveCount(0);
  expect(called).toBe(false);
});

// 8. User-reported-submission: confirms with the exact POST body contract and updates the badge.
test('confirming submission sends the exact request body and reflects USER_REPORTED_SUBMITTED', async ({ page }) => {
  const session = submissionSession();
  await openWaitingRow(page, session);

  let body: unknown = null;
  await page.route('**/api/application-submission/*/report-submitted', async (route) => {
    body = route.request().postDataJSON();
    await route.fulfill({ json: { ...session, status: 'USER_REPORTED_SUBMITTED', userReportedSubmittedAt: '2026-08-09T12:00:00Z', userSubmissionNote: 'Confirmation #ABC123' } });
  });

  await page.getByRole('button', { name: 'Mark as submitted' }).click();
  await page.getByPlaceholder('Optional note — e.g. a confirmation number or reference ID').fill('Confirmation #ABC123');
  await page.getByRole('button', { name: 'Yes, I submitted it' }).click();

  await expect(page.getByText('Marked as submitted')).toBeVisible();
  expect(body).toEqual({ note: 'Confirmation #ABC123' });
});

// 9. Double-click / race protection: the confirm button disables mid-flight so a second click can't double-fire.
test('double-clicking "Yes, I submitted it" only fires one request', async ({ page }) => {
  const session = submissionSession();
  await openWaitingRow(page, session);

  let calls = 0;
  await page.route('**/api/application-submission/*/report-submitted', async (route) => {
    calls += 1;
    await new Promise((r) => setTimeout(r, 400));
    await route.fulfill({ json: { ...session, status: 'USER_REPORTED_SUBMITTED' } });
  });

  await page.getByRole('button', { name: 'Mark as submitted' }).click();
  const confirmBtn = page.getByRole('button', { name: 'Yes, I submitted it' });
  await confirmBtn.click();
  await confirmBtn.click({ force: true }).catch(() => {}); // second click races the disabled/loading state
  await page.waitForTimeout(600);
  expect(calls).toBe(1);
});

// 10. 409 (already resolved elsewhere) surfaces the honest error toast, doesn't crash.
test('a 409 from report-submitted shows the "may no longer be awaiting" error toast', async ({ page }) => {
  const session = submissionSession();
  await openWaitingRow(page, session);
  await page.route('**/api/application-submission/*/report-submitted', (route) =>
    route.fulfill({ status: 409, json: { message: 'Session is not awaiting manual submission' } }),
  );
  await page.getByRole('button', { name: 'Mark as submitted' }).click();
  await page.getByRole('button', { name: 'Yes, I submitted it' }).click();
  await expect(page.getByText('Could not update')).toBeVisible();
  await expect(page.getByText('This application may no longer be awaiting manual submission.')).toBeVisible();
});

// 11. 500 handling — same graceful error path, no unhandled rejection / crash.
test('a 500 from report-submitted shows the error toast without crashing the page', async ({ page }) => {
  const session = submissionSession();
  await openWaitingRow(page, session);
  await page.route('**/api/application-submission/*/report-submitted', (route) => route.fulfill({ status: 500, json: { message: 'boom' } }));
  await page.getByRole('button', { name: 'Mark as submitted' }).click();
  await page.getByRole('button', { name: 'Yes, I submitted it' }).click();
  await expect(page.getByText('Could not update')).toBeVisible();
  // Page still responsive — dialog can still be dismissed.
  await expect(page.getByRole('button', { name: 'Mark as submitted' })).toBeVisible();
});

// 16. Resolved-submission-states — USER_REPORTED_SUBMITTED renders its own honest, non-verified copy.
test('USER_REPORTED_SUBMITTED status renders the "not verified, your own record" copy', async ({ page }) => {
  const session = submissionSession({
    status: 'USER_REPORTED_SUBMITTED',
    userReportedSubmittedAt: '2026-08-09T12:00:00Z',
    userSubmissionNote: 'Confirmation #ABC123',
  });
  await openWaitingRow(page, session);
  await expect(page.getByText('You reported this as submitted')).toBeVisible();
  await expect(page.getByText(/CareerPilot did not verify this itself/)).toBeVisible();
  await expect(page.getByText('Note: Confirmation #ABC123')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Mark as submitted' })).toHaveCount(0);
});
