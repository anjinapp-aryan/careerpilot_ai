import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/testUtils';
import { GuidedApplyBriefPanel } from './GuidedApplyBriefPanel';
import { guidedApply } from '@/lib/guidedApply';
import type { ApplicationCard, GuidedApplyBrief } from '@/types/workflow';

vi.mock('@/lib/guidedApply', () => ({ guidedApply: { brief: vi.fn() } }));
vi.mock('@/lib/executionEvidence', () => ({
  executionEvidence: { get: vi.fn().mockResolvedValue({ hasExecution: false, state: 'NOT_STARTED', automationStarted: false, instrumentationEnabled: null, timeline: [] }) },
}));

function card(overrides: Partial<ApplicationCard> = {}): ApplicationCard {
  return {
    id: 'app-1',
    jobId: 'job-1',
    status: 'APPLIED',
    favorite: false,
    priority: 'MEDIUM',
    archived: false,
    createdAt: '2026-01-01T00:00:00Z',
    resumeTailored: false,
    atsAnalysisReady: false,
    coverLetterReady: false,
    applicationPackageReady: false,
    applicationReviewReady: false,
    healthStatus: 'HEALTHY',
    healthScore: 80,
    healthReasoning: '',
    recommendationAction: 'WAIT',
    recommendationReasoning: '',
    suggestedNextAction: '',
    guidedApplyRequired: true,
    blockerReason: 'CAPTCHA',
    ...overrides,
  };
}

const EMPTY_BRIEF: GuidedApplyBrief = { profile: [], recommendedAnswers: [] };

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe('GuidedApplyBriefPanel — why-manual banner + blocker reasons (Test R)', () => {
  it.each([
    ['CAPTCHA'],
    ['BOT_PROTECTION'],
    ['LOGIN_REQUIRED'],
    ['UNSUPPORTED_CONTROL'],
    ['EMPLOYER_RESTRICTION'],
    ['AUTOMATION_BLOCKED'],
    ['MANUAL_REQUIRED'],
    ['UNKNOWN_BLOCKER'],
  ])('maps blockerReason=%s to a human-readable explanation, never the raw enum token', async (reason) => {
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card({ blockerReason: reason })} />);

    expect(await screen.findByText('Manual completion required')).toBeInTheDocument();
    // The raw underscored enum token (e.g. "UNSUPPORTED_CONTROL") must never appear verbatim.
    expect(screen.queryByText(reason)).not.toBeInTheDocument();
  });
});

describe('GuidedApplyBriefPanel — employer URL safety (Tests D, E)', () => {
  it('never calls window.open on render alone', async () => {
    const openSpy = vi.fn();
    vi.stubGlobal('open', openSpy);
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);

    renderWithProviders(
      <GuidedApplyBriefPanel applicationId="app-1" card={card({ externalUrl: 'https://employer.example/jobs/123' })} />,
    );
    await screen.findByText('Manual completion required');

    expect(openSpy).not.toHaveBeenCalled();
  });

  it('opens the exact employer URL in a new tab only after an explicit click, with no rewriting', async () => {
    const openSpy = vi.fn();
    vi.stubGlobal('open', openSpy);
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    const user = userEvent.setup();

    renderWithProviders(
      <GuidedApplyBriefPanel applicationId="app-1" card={card({ externalUrl: 'https://employer.example/jobs/123?ref=x' })} />,
    );
    await user.click(screen.getByRole('button', { name: 'Open employer application in a new tab' }));

    expect(openSpy).toHaveBeenCalledTimes(1);
    expect(openSpy).toHaveBeenCalledWith(
      'https://employer.example/jobs/123?ref=x',
      '_blank',
      'noopener,noreferrer',
    );
  });

  it('shows an honest unavailable message and no CTA when the employer URL is missing — never a fabricated URL', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card({ externalUrl: null })} />);

    expect(await screen.findByText(/employer application link unavailable/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Open employer application in a new tab' })).not.toBeInTheDocument();
  });

  /**
   * Final Hardening Pass — Job.externalUrl is external, provider-supplied data. A `javascript:`
   * (or any non-http(s)) scheme must be treated exactly like a missing URL: no CTA rendered, so
   * there is no way to trigger it at all — not merely "window.open would refuse it".
   */
  it.each([
    ['javascript:alert(1)'],
    ['data:text/html,<script>alert(1)</script>'],
    ['not a url'],
    ['ftp://files.example.com/app'],
  ])('treats an unsafe or unparseable URL (%s) exactly like a missing one — no CTA at all', async (unsafeUrl) => {
    const openSpy = vi.fn();
    vi.stubGlobal('open', openSpy);
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card({ externalUrl: unsafeUrl })} />);

    expect(await screen.findByText(/employer application link unavailable/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Open employer application in a new tab' })).not.toBeInTheDocument();
    expect(openSpy).not.toHaveBeenCalled();
  });

  it('still opens a genuinely safe https URL — the safety check is not overly strict', async () => {
    const openSpy = vi.fn();
    vi.stubGlobal('open', openSpy);
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    const user = userEvent.setup();

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card({ externalUrl: 'https://boards.greenhouse.io/acme/jobs/1' })} />);
    await user.click(screen.getByRole('button', { name: 'Open employer application in a new tab' }));

    expect(openSpy).toHaveBeenCalledWith('https://boards.greenhouse.io/acme/jobs/1', '_blank', 'noopener,noreferrer');
  });
});

describe('GuidedApplyBriefPanel — recommended answers (Tests F, G, T, U)', () => {
  it('renders a resolved answer with question, value, source and confidence (Test F)', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue({
      profile: [],
      recommendedAnswers: [
        {
          question: 'Will you require sponsorship?',
          canonicalField: 'VISA_SPONSORSHIP',
          value: 'No',
          source: 'CandidateProfile.visaRequired',
          confidence: 'HIGH',
          needsUserInput: false,
        },
      ],
    });

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);

    expect(await screen.findByText('Will you require sponsorship?')).toBeInTheDocument();
    expect(screen.getByText('No')).toBeInTheDocument();
    expect(screen.getByText(/CandidateProfile\.visaRequired/)).toBeInTheDocument();
    expect(screen.getByText(/HIGH/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /copy/i })).toBeInTheDocument();
  });

  it('never fabricates an unresolved answer — shows "needs your input", not a guessed value (Test G)', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue({
      profile: [],
      recommendedAnswers: [
        {
          question: 'What is your notice period?',
          canonicalField: 'NOTICE_PERIOD',
          value: null,
          source: null,
          confidence: null,
          needsUserInput: true,
        },
      ],
    });

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);

    expect(await screen.findByText('What is your notice period?')).toBeInTheDocument();
    expect(screen.getByText(/needs your input/i)).toBeInTheDocument();
    expect(screen.queryByText(/^yes$/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/^no$/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/^n\/a$/i)).not.toBeInTheDocument();
    // No copy affordance for an answer that doesn't exist.
    expect(screen.queryByRole('button', { name: /copy/i })).not.toBeInTheDocument();
  });

  it('shows an explicit empty state rather than nothing when there are no recommended answers (Test U)', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);

    expect(await screen.findByText(/no recommended answers available/i)).toBeInTheDocument();
  });

  it('renders only profile facts the API actually returned — no hardcoded candidate facts (Test T)', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue({
      profile: [{ label: 'Name', value: 'Ada Lovelace', source: 'User.fullName' }],
      recommendedAnswers: [],
    });
    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);

    await screen.findByText('Ada Lovelace');
    // None of the mission's own worked examples must ever appear unless the mock supplied them.
    for (const forbidden of ['12+ years', 'Authorized', '30 days', 'Aryan Anjinappa']) {
      expect(screen.queryByText(forbidden)).not.toBeInTheDocument();
    }
  });

  it('shows an honest empty state for profile facts when none resolved', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);

    expect(await screen.findByText(/no verified profile facts available yet/i)).toBeInTheDocument();
  });

  it('shows both the empty-facts message AND "Resume not available" when neither resolved — never silently drops one', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);

    expect(await screen.findByText(/no verified profile facts available yet/i)).toBeInTheDocument();
    expect(screen.getByText('Resume not available')).toBeInTheDocument();
  });

  it('shows "Resume not available" rather than a blank/undefined row when no resume resolved', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue({
      profile: [{ label: 'Name', value: 'Ada Lovelace', source: 'User.fullName' }],
      recommendedAnswers: [],
    });
    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);

    expect(await screen.findByText('Resume not available')).toBeInTheDocument();
  });
});

/**
 * happy-dom's `navigator` is a getter-only accessor, so `vi.stubGlobal('navigator', ...)`
 * silently fails to replace it. Defining `navigator.clipboard` directly (the standard pattern for
 * mocking this specific browser API) is what actually reaches the component's own
 * `navigator.clipboard.writeText(...)` call.
 */
function stubClipboard(writeText: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  });
}

describe('GuidedApplyBriefPanel — clipboard safety (Tests H, I)', () => {
  afterEach(() => {
    // @ts-expect-error test-only cleanup of a property this suite itself defined
    delete navigator.clipboard;
  });

  function twoAnswers() {
    return {
      profile: [],
      recommendedAnswers: [
        {
          question: 'Work authorization?',
          canonicalField: 'WORK_AUTHORIZATION',
          value: 'Authorized',
          source: 'CandidateAtsProfile.workAuthorization',
          confidence: 'HIGH' as const,
          needsUserInput: false,
        },
        {
          question: 'Sponsorship required?',
          canonicalField: 'VISA_SPONSORSHIP',
          value: 'No',
          source: 'CandidateProfile.visaRequired',
          confidence: 'HIGH' as const,
          needsUserInput: false,
        },
      ],
    };
  }

  it('never writes to the clipboard on render alone', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    stubClipboard(writeText);
    vi.mocked(guidedApply.brief).mockResolvedValue(twoAnswers());

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);
    await screen.findByText('Work authorization?');

    expect(writeText).not.toHaveBeenCalled();
  });

  it('copies exactly the clicked answer\'s value, once, after explicit click — never other answers', async () => {
    // user-event's own setup() installs its own navigator.clipboard mock — stubbing must happen
    // AFTER setup(), or user-event's stub silently clobbers ours.
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    stubClipboard(writeText);
    vi.mocked(guidedApply.brief).mockResolvedValue(twoAnswers());

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);
    await screen.findByText('Work authorization?');

    const copyButtons = screen.getAllByRole('button', { name: /copy/i });
    expect(copyButtons).toHaveLength(2);

    // Copy the SECOND answer only.
    await user.click(copyButtons[1]);

    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith('No');
    expect(writeText).not.toHaveBeenCalledWith('Authorized');
  });

  it('reports failure honestly when the clipboard API rejects, without crashing', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    stubClipboard(writeText);
    vi.mocked(guidedApply.brief).mockResolvedValue(twoAnswers());

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);
    await screen.findByText('Work authorization?');
    await user.click(screen.getAllByRole('button', { name: /copy/i })[0]);

    expect(await screen.findByText(/could not copy/i)).toBeInTheDocument();
  });
});

describe('GuidedApplyBriefPanel — checklist (Tests J, W)', () => {
  it('starts every step unchecked', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);

    const label = await screen.findByText('Confirm job title');
    expect(label.className).not.toContain('line-through');
  });

  it('lets the user explicitly check a step, and persists it across remounts', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    const user = userEvent.setup();

    const { unmount } = renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);
    await user.click(screen.getByText('Confirm job title'));
    unmount();

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-1" card={card()} />);
    const label = await screen.findByText('Confirm job title');
    expect(label.className).toContain('line-through');
  });

  it('isolates checklist state per application — checking app-100 must never affect app-200 (Test W)', async () => {
    vi.mocked(guidedApply.brief).mockResolvedValue(EMPTY_BRIEF);
    const user = userEvent.setup();

    const { unmount } = renderWithProviders(<GuidedApplyBriefPanel applicationId="app-100" card={card({ id: 'app-100' })} />);
    await user.click(screen.getByText('Upload resume'));
    unmount();

    renderWithProviders(<GuidedApplyBriefPanel applicationId="app-200" card={card({ id: 'app-200' })} />);
    const label = await screen.findByText('Upload resume');
    expect(label.className).not.toContain('line-through');
  });
});
