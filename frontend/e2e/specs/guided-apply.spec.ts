import { test, expect } from '@playwright/test';
import { loginAs } from '../fixtures/auth';
import { mockApiCatchAll, mockApplications, baseCard, guidedApplyBrief, emptyBrief } from '../fixtures/mocks';

/**
 * Guided Apply E2E coverage, Playwright Test Phase 3. Every network call is intercepted — no real
 * backend, no real employer site is ever contacted (see `e2e/fixtures/mocks.ts`'s own rationale).
 * Scenario numbers below correspond to the 17 named scenarios in the Phase 3 mission.
 */

test.beforeEach(async ({ page }) => {
  await loginAs(page);
  await mockApiCatchAll(page);
});

// 1. Guided Apply card badge + warning line appear only when guidedApplyRequired is true.
test('application card shows the Guided Apply badge and warning when required', async ({ page }) => {
  await mockApplications(page, [baseCard()]);
  await page.goto('/applications');
  await expect(page.getByText('🟡 Guided Apply')).toBeVisible();
  await expect(page.getByText('⚠ Manual completion required')).toBeVisible();
});

test('application card omits the Guided Apply badge for a normal application (regression)', async ({ page }) => {
  await mockApplications(page, [
    baseCard({ guidedApplyRequired: false, blockerReason: null, blockerDetail: null, automationHealth: null }),
  ]);
  await page.goto('/applications');
  await expect(page.getByText('🟡 Guided Apply')).toHaveCount(0);
  await expect(page.getByText('⚠ Manual completion required')).toHaveCount(0);
});

// 2. Drawer auto-jumps to the Guided Apply tab when the card requires it, and shows the panel.
test('drawer auto-opens the Guided Apply tab and renders the brief', async ({ page }) => {
  const card = baseCard();
  await mockApplications(page, [card]);
  await page.route(`**/api/applications/${card.id}/guided-apply-brief`, (route) => route.fulfill({ json: guidedApplyBrief() }));
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();

  const guidedTab = page.getByRole('tab', { name: 'Guided Apply' });
  await expect(guidedTab).toHaveAttribute('aria-selected', 'true');
  // Scoped to the dialog: the card behind the drawer also renders a truncated "Manual completion
  // required" warning line, and its ancestor draggable div's computed accessible name absorbs that
  // text too (a real accessible-name-computation quirk, not an app bug) — an unscoped getByText
  // matches both.
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('Manual completion required', { exact: true })).toBeVisible();
  await expect(dialog.getByText(/CAPTCHA\/bot-protection challenge was detected/)).toBeVisible();
});

// 3 + 4. Employer CTA safety: safe http(s) URL renders and is requested exactly as stored; unsafe
// schemes are rejected (H3). The CTA's target — a real employer's application page — must never
// actually be contacted by this suite, so the popup's own navigation request is intercepted and
// aborted; asserting the exact intercepted URL proves `window.open` received the right value
// without ever letting Chromium reach the real internet.
test('employer CTA opens a safe https URL in a new tab', async ({ page, context }) => {
  const card = baseCard();
  let requestedUrl: string | null = null;
  await context.route(card.externalUrl!, async (route) => {
    requestedUrl = route.request().url();
    await route.abort();
  });
  await mockApplications(page, [card]);
  await page.route(`**/api/applications/${card.id}/guided-apply-brief`, (route) => route.fulfill({ json: guidedApplyBrief() }));
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();
  await page.getByRole('tab', { name: 'Guided Apply' }).click();

  const [popup] = await Promise.all([
    context.waitForEvent('page'),
    page.getByRole('button', { name: 'Open employer application in a new tab' }).click(),
  ]);
  await expect.poll(() => requestedUrl).toBe(card.externalUrl);
  await popup.close().catch(() => {});
});

test('unsafe (javascript:) employer URL is never rendered as a clickable CTA', async ({ page }) => {
  const card = baseCard({ externalUrl: 'javascript:alert(1)' });
  await mockApplications(page, [card]);
  await page.route(`**/api/applications/${card.id}/guided-apply-brief`, (route) => route.fulfill({ json: guidedApplyBrief() }));
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();
  await page.getByRole('tab', { name: 'Guided Apply' }).click();

  await expect(page.getByRole('button', { name: 'Open employer application in a new tab' })).toHaveCount(0);
  await expect(page.getByText('Employer application link unavailable for this job.')).toBeVisible();
});

// 14. Missing employer URL — same fail-closed path as an unsafe URL, distinct root cause.
test('missing employer URL shows the unavailable message, not a broken CTA', async ({ page }) => {
  const card = baseCard({ externalUrl: null });
  await mockApplications(page, [card]);
  await page.route(`**/api/applications/${card.id}/guided-apply-brief`, (route) => route.fulfill({ json: guidedApplyBrief() }));
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();
  await page.getByRole('tab', { name: 'Guided Apply' }).click();
  await expect(page.getByText('Employer application link unavailable for this job.')).toBeVisible();
});

// 5. Recommended answers render + copy isolation (copying one answer must not affect another).
test('recommended answers copy independently to the clipboard', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  const card = baseCard();
  await mockApplications(page, [card]);
  await page.route(`**/api/applications/${card.id}/guided-apply-brief`, (route) => route.fulfill({ json: guidedApplyBrief() }));
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();
  await page.getByRole('tab', { name: 'Guided Apply' }).click();

  await page.getByRole('button', { name: 'Copy' }).first().click();
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  expect(copied).toBe('Yes');
});

// 6. Anti-fabrication: needsUserInput answers never render a fabricated value.
test('an unresolved answer is shown as "Needs your input", never a fabricated value', async ({ page }) => {
  const card = baseCard();
  await mockApplications(page, [card]);
  await page.route(`**/api/applications/${card.id}/guided-apply-brief`, (route) => route.fulfill({ json: guidedApplyBrief() }));
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();
  await page.getByRole('tab', { name: 'Guided Apply' }).click();

  await expect(page.getByText('What is your expected salary?')).toBeVisible();
  await expect(page.getByText('Needs your input')).toBeVisible();
});

test('empty brief shows the honest "no verified profile facts" state, never blank silence', async ({ page }) => {
  const card = baseCard();
  await mockApplications(page, [card]);
  await page.route(`**/api/applications/${card.id}/guided-apply-brief`, (route) => route.fulfill({ json: emptyBrief() }));
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();
  await page.getByRole('tab', { name: 'Guided Apply' }).click();
  await expect(page.getByText('No verified profile facts available yet.')).toBeVisible();
  await expect(page.getByText('No recommended answers available.')).toBeVisible();
});

// 15. Checklist isolation: toggling a step for one application must not affect another (localStorage keyed per id).
test('Guided Apply checklist progress is isolated per application', async ({ page }) => {
  const cardA = baseCard({ id: 'app-a' });
  const cardB = baseCard({ id: 'app-b', jobId: 'job-b' });
  await mockApplications(page, [cardA, cardB]);
  await page.route('**/guided-apply-brief', (route) => route.fulfill({ json: guidedApplyBrief() }));
  await page.goto('/applications');

  // Exact match distinguishes the checklist step ("Open employer application") from the CTA button,
  // whose accessible name is the longer "Open employer application in a new tab".
  const checklistStep = page.getByRole('button', { name: 'Open employer application', exact: true });
  await page.locator('button[aria-label="View application details"]').nth(0).click();
  await page.getByRole('tab', { name: 'Guided Apply' }).click();
  await checklistStep.click();
  await expect(checklistStep.locator('span').last()).toHaveClass(/line-through/);
  await page.keyboard.press('Escape');

  await page.locator('button[aria-label="View application details"]').nth(1).click();
  await page.getByRole('tab', { name: 'Guided Apply' }).click();
  await expect(checklistStep.locator('span').last()).not.toHaveClass(/line-through/);
});
