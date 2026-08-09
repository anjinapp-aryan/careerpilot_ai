import type { Page } from '@playwright/test';

/**
 * All backend calls in this suite are intercepted with `page.route(...)` rather than hitting a
 * real backend/DB — this repo's only real E2E-usable backend is the live Neon-backed Oracle Cloud
 * deployment (see CLAUDE.md), which this suite must never touch. Route handlers are matched
 * most-recently-registered-first, so `mockApiCatchAll` (broad, safe defaults for every endpoint
 * the app calls on mount) must be installed BEFORE any test-specific override.
 */
export async function mockApiCatchAll(page: Page) {
  // Anything not explicitly overridden below gets an empty-but-valid response instead of a real
  // network call to the (very likely not running) backend at localhost:8080 — keeps every test
  // deterministic and fast regardless of what a given page happens to fetch on mount.
  await page.route('**/api/**', async (route) => {
    const url = route.request().url();
    if (url.includes('/guided-apply-brief')) return route.fulfill({ json: emptyBrief() });
    if (url.includes('/execution-evidence')) return route.fulfill({ json: notStartedEvidence() });
    if (url.includes('/api/diagnostics/')) return route.fulfill({ json: {} });
    // Every other GET this app calls on mount (execution approvals, application-submission list,
    // career-timeline, etc.) expects an array and does `(data ?? []).filter/.map` with no
    // try/catch — an object response crashes the React tree (real bug found via a diagnostic run:
    // `ExecutionApprovalsPanel` threw `(pending.data ?? []).filter is not a function` and the
    // resulting render-crash loop made every element appear to "detach from the DOM" mid-test).
    // Non-GET (POST/PUT/DELETE) mutations fall through to an empty object, which is fine since
    // every test that cares about a mutation's response registers its own override afterward.
    if (route.request().method() === 'GET') return route.fulfill({ json: [] });
    return route.fulfill({ json: {} });
  });
}

/**
 * `ApplicationDrawer` fetches its own card via `GET /api/applications/{id}` — a DIFFERENT endpoint
 * from the board's `GET /api/applications/cards` list (real gap found while writing this suite:
 * mocking only the list left the drawer's query unmocked, falling through to the array-shaped
 * catch-all default, so `card` was `[]` and `card.guidedApplyRequired` was always undefined).
 * Registers both so a card opened in the drawer carries the exact same fields as in the list.
 */
export async function mockApplications(page: Page, cards: ReturnType<typeof baseCard>[]) {
  await page.route('**/api/applications/cards', (route) => route.fulfill({ json: cards }));
  for (const card of cards) {
    await page.route(`**/api/applications/${card.id}`, (route) => route.fulfill({ json: card }));
  }
}

/** P7 Action 7 — the honest default `GET /{id}/execution-evidence` response for a card with no
 *  automation execution. Override per-test via `executionEvidence(...)`. */
export function notStartedEvidence(overrides: Record<string, unknown> = {}) {
  return {
    hasExecution: false,
    state: 'NOT_STARTED',
    automationStarted: false,
    instrumentationEnabled: null,
    timeline: [],
    ...overrides,
  };
}

export function executionEvidence(overrides: Record<string, unknown> = {}) {
  return {
    hasExecution: true,
    executionId: 'exec-1',
    executionStatus: 'ABORTED',
    instrumentationEnabled: true,
    automationStarted: true,
    state: 'STOPPED',
    employerPageReached: null,
    formDiscovered: null,
    captchaOrLoginDetected: null,
    fieldsDiscovered: null,
    fieldsFilled: null,
    fieldsResolved: null,
    fieldsUnresolved: null,
    automationStopped: true,
    stopReason: null,
    stoppedAtStage: null,
    failureCategory: null,
    timeline: [],
    ...overrides,
  };
}

export function emptyBrief() {
  return { candidateName: null, candidateEmail: null, resumeFilename: null, profile: [], recommendedAnswers: [] };
}

/** Base ApplicationCard — matches `types/workflow.ts#ApplicationCard`. Override per-test as needed. */
export function baseCard(overrides: Record<string, unknown> = {}) {
  return {
    id: 'app-0000-0000-0000-000000000001',
    jobId: 'job-0000-0000-0000-000000000001',
    resumeId: 'resume-1',
    status: 'APPLIED',
    matchScore: 82,
    atsScore: 74,
    nextAction: null,
    nextActionAt: null,
    notes: null,
    favorite: false,
    priority: 'MEDIUM',
    archived: false,
    createdAt: '2026-08-01T10:00:00Z',
    updatedAt: '2026-08-01T10:00:00Z',
    jobTitle: 'Senior Backend Engineer',
    company: 'Acme Corp',
    location: 'Berlin, Germany',
    salaryRange: '€90k-€120k',
    remoteType: 'HYBRID',
    source: 'greenhouse',
    externalUrl: 'https://boards.greenhouse.io/acme/jobs/123456',
    sponsorshipAvailable: true,
    employerJobId: 'gh-123456',
    resumeTailored: true,
    atsAnalysisReady: true,
    coverLetterReady: false,
    applicationPackageReady: true,
    applicationReviewReady: false,
    visaRequired: false,
    healthStatus: 'ON_TRACK',
    healthScore: 80,
    healthReasoning: 'Application is progressing normally.',
    recommendationAction: 'WAIT',
    recommendationReasoning: 'No action needed right now.',
    suggestedNextAction: 'Wait for employer response',
    suggestedNextActionAt: null,
    lifecycleStatus: null,
    executionId: 'exec-1',
    executionStatus: 'ABORTED',
    automationHealth: 'GUIDED_APPLY_REQUIRED',
    retryCount: 1,
    verificationStatus: null,
    guidedApplyRequired: true,
    blockerReason: 'CAPTCHA',
    blockerDetail: 'reCAPTCHA v3 challenge detected on submit',
    ...overrides,
  };
}

export function guidedApplyBrief(overrides: Record<string, unknown> = {}) {
  return {
    candidateName: 'E2E Test User',
    candidateEmail: 'e2e-user@example.test',
    resumeFilename: 'e2e-user-resume.pdf',
    profile: [
      { label: 'Full Name', value: 'E2E Test User', source: 'User' },
      { label: 'Email', value: 'e2e-user@example.test', source: 'User' },
    ],
    recommendedAnswers: [
      {
        question: 'Are you legally authorized to work in this country?',
        canonicalField: 'WORK_AUTHORIZATION',
        value: 'Yes',
        source: 'CandidateProfile',
        confidence: 'HIGH',
        needsUserInput: false,
      },
      {
        question: 'What is your expected salary?',
        canonicalField: 'SALARY_EXPECTATION',
        value: null,
        source: null,
        confidence: null,
        needsUserInput: true,
      },
    ],
    ...overrides,
  };
}

export function submissionSession(overrides: Record<string, unknown> = {}) {
  return {
    id: 'session-0000-0000-0000-000000000001',
    userId: '11111111-1111-1111-1111-111111111111',
    jobId: 'job-0000-0000-0000-000000000001',
    applicationId: 'app-0000-0000-0000-000000000001',
    resumeId: 'resume-1',
    resumeTailoringId: 'rt-1',
    applicationPackageId: 'pkg-1',
    applicationReviewId: null,
    companyKnowledgeId: null,
    approvalQueueEntryId: null,
    applicationExecutionId: 'exec-1',
    status: 'WAITING_MANUAL_SUBMISSION',
    submissionMethod: 'MANUAL',
    provider: 'greenhouse',
    correlationId: 'corr-1',
    failureReason: null,
    startedAt: '2026-08-01T10:00:00Z',
    completedAt: null,
    createdAt: '2026-08-01T10:00:00Z',
    updatedAt: '2026-08-01T10:00:00Z',
    userReportedSubmittedAt: null,
    userSubmissionNote: null,
    ...overrides,
  };
}
