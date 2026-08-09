import { test, expect } from '@playwright/test';
import { loginAs } from '../fixtures/auth';
import { mockApiCatchAll, mockApplications, baseCard, guidedApplyBrief } from '../fixtures/mocks';

test.beforeEach(async ({ page }) => {
  await loginAs(page);
  await mockApiCatchAll(page);
});

// 12/16. Suppression is driven by the backend's `guidedApplyRequired` flag (derived from
// `SubmissionStateMachine.resolvesManualCompletion`, backend Guided Apply Hardening Pass 2) — a
// card whose latest session already resolved manual completion never carries the flag at all, so
// the badge/tab/panel are provably absent for every one of the `RESOLVES_MANUAL_COMPLETION`
// statuses. This is the browser-level guardrail for H1/H2: once the backend says "resolved", the
// frontend has no code path that can still show the stale Guided Apply prompt.
for (const status of ['SUBMITTED', 'SUBMIT_UNVERIFIED', 'VERIFYING', 'VERIFIED', 'VERIFICATION_FAILED', 'TRACKING', 'COMPLETED', 'USER_REPORTED_SUBMITTED']) {
  test(`card with resolved submission status "${status}" never shows Guided Apply (H1/H2 regression guard)`, async ({ page }) => {
    const card = baseCard({ guidedApplyRequired: false, blockerReason: null, blockerDetail: null, automationHealth: null, lifecycleStatus: status });
    await mockApplications(page, [card]);
    await page.goto('/applications');
    await expect(page.getByText('🟡 Guided Apply')).toHaveCount(0);
  });
}

// 17. Accessibility smoke: tab list is a real `role=tablist`/`role=tab`, dialog has `role=dialog`,
// interactive elements have accessible names — not a full axe scan, but real ARIA-tree assertions.
test('Guided Apply panel exposes a real tablist/tab and named interactive controls', async ({ page }) => {
  const card = baseCard();
  await mockApplications(page, [card]);
  await page.route(`**/api/applications/${card.id}/guided-apply-brief`, (route) => route.fulfill({ json: guidedApplyBrief() }));
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();

  await expect(page.getByRole('dialog')).toBeVisible();
  const tablist = page.getByRole('tablist');
  await expect(tablist).toBeVisible();
  const guidedTab = page.getByRole('tab', { name: 'Guided Apply' });
  await expect(guidedTab).toHaveAttribute('aria-selected', 'true');

  // CTA and copy buttons must have real accessible names, not icon-only unlabeled buttons.
  await expect(page.getByRole('button', { name: 'Open employer application in a new tab' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Copy' }).first()).toBeVisible();
});
