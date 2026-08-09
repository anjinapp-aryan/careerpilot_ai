import { test, expect } from '@playwright/test';
import { loginAs } from '../fixtures/auth';
import { mockApiCatchAll, mockApplications, baseCard, executionEvidence } from '../fixtures/mocks';

/**
 * P7 Action 7 — Execution Visibility. Real Chromium, every backend call intercepted (see
 * `e2e/fixtures/mocks.ts`) — never a real employer site, never real automation. Covers the
 * `ExecutionEvidencePanel`/`ExecutionEvidenceSummary` truth-model rendering named in the Phase 14
 * E2E list: CAPTCHA/login stop, unsupported-control stop, partial fill, form discovered,
 * completed run, and "no evidence" honesty.
 */

test.beforeEach(async ({ page }) => {
  await loginAs(page);
  await mockApiCatchAll(page);
});

async function openAutomationTab(page: import('@playwright/test').Page, card: ReturnType<typeof baseCard>) {
  await mockApplications(page, [card]);
  await page.goto('/applications');
  await page.locator('button[aria-label="View application details"]').first().click();
  await page.getByRole('tab', { name: 'Automation' }).click();
}

test('CAPTCHA/login stop: employer page reached, form not discovered, honest stop reason shown', async ({ page }) => {
  const card = baseCard();
  await page.route(`**/api/applications/${card.id}/execution-evidence`, (route) =>
    route.fulfill({
      json: executionEvidence({
        state: 'EMPLOYER_PAGE_REACHED',
        employerPageReached: true,
        formDiscovered: false,
        captchaOrLoginDetected: true,
        stopReason: 'captcha or login wall detected — routed to human review',
      }),
    }),
  );
  await openAutomationTab(page, card);

  await expect(page.getByTestId('execution-evidence-summary')).toBeVisible();
  await expect(page.getByText('Automation reached the employer page')).toBeVisible();
  await expect(page.getByText(/Employer page: Reached/)).toBeVisible();
  await expect(page.getByText(/Form discovered: Not reached/)).toBeVisible();
  await expect(page.getByText(/captcha or login wall detected/)).toBeVisible();
});

test('unsupported-control / partial fill: reports PARTIALLY_FILLED with real field counts', async ({ page }) => {
  const card = baseCard();
  await page.route(`**/api/applications/${card.id}/execution-evidence`, (route) =>
    route.fulfill({
      json: executionEvidence({
        state: 'PARTIALLY_FILLED',
        employerPageReached: true,
        formDiscovered: true,
        fieldsDiscovered: 18,
        fieldsFilled: 12,
        fieldsResolved: 12,
        fieldsUnresolved: 6,
        stopReason: 'required fields could not be filled from verified data: Visa Sponsorship: no supported control',
        timeline: [
          { sequence: 1, stage: 'NAVIGATION_STARTED', displayName: 'Navigation Started', status: 'COMPLETED' },
          { sequence: 2, stage: 'NAVIGATION_COMPLETED', displayName: 'Navigation Completed', status: 'COMPLETED' },
          { sequence: 3, stage: 'FORM_DISCOVERED', displayName: 'Form Discovered', status: 'COMPLETED' },
          { sequence: 4, stage: 'FIELD_FILL_COMPLETED', displayName: 'Field Fill Completed', status: 'FAILED', reason: 'required fields could not be filled from verified data: Visa Sponsorship: no supported control' },
        ],
      }),
    }),
  );
  await openAutomationTab(page, card);

  await expect(page.getByText('Automation partially filled the form, then stopped')).toBeVisible();
  await expect(page.getByText(/Fields filled: 12/)).toBeVisible();
  await expect(page.getByText('Form Discovered', { exact: true })).toBeVisible();
  await expect(page.getByText(/no supported control/).first()).toBeVisible();
});

test('form discovered but fill never completed shows FILLING state honestly (in-flight, not stopped)', async ({ page }) => {
  const card = baseCard();
  await page.route(`**/api/applications/${card.id}/execution-evidence`, (route) =>
    route.fulfill({
      json: executionEvidence({
        state: 'FILLING',
        employerPageReached: true,
        formDiscovered: true,
        fieldsDiscovered: 10,
        automationStopped: false,
        stopReason: null,
      }),
    }),
  );
  await openAutomationTab(page, card);

  await expect(page.getByText('Automation was filling the form')).toBeVisible();
  await expect(page.getByText(/Stopped:/)).toHaveCount(0);
});

test('completed run reports 🟢 Automation completed with real field counts', async ({ page }) => {
  const card = baseCard({ guidedApplyRequired: false, blockerReason: null, blockerDetail: null, automationHealth: 'COMPLETED' });
  await page.route(`**/api/applications/${card.id}/execution-evidence`, (route) =>
    route.fulfill({
      json: executionEvidence({
        state: 'COMPLETED',
        employerPageReached: true,
        formDiscovered: true,
        fieldsFilled: 20,
        automationStopped: false,
        stopReason: null,
      }),
    }),
  );
  await openAutomationTab(page, card);

  await expect(page.getByText('🟢 Automation completed')).toBeVisible();
  await expect(page.getByText(/Fields filled: 20/)).toBeVisible();
});

test('no automation attempt yet: honest "not started" message, never a fabricated status', async ({ page }) => {
  const card = baseCard({ guidedApplyRequired: false, blockerReason: null, blockerDetail: null, executionId: null, automationHealth: null });
  // No execution-evidence override — falls through to mockApiCatchAll's honest NOT_STARTED default.
  await openAutomationTab(page, card);

  await expect(page.getByText('No automation execution has run for this application yet.')).toBeVisible();
});

test('technical details are collapsed by default and reveal raw evidence on click', async ({ page }) => {
  const card = baseCard();
  await page.route(`**/api/applications/${card.id}/execution-evidence`, (route) =>
    route.fulfill({ json: executionEvidence({ state: 'COMPLETED', automationStopped: false }) }),
  );
  await openAutomationTab(page, card);

  await expect(page.getByText(/"executionId"/)).toHaveCount(0);
  await page.getByRole('button', { name: /Show technical details/ }).click();
  await expect(page.getByText(/"executionId"/)).toBeVisible();
});
