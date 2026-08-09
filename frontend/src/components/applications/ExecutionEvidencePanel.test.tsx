import { describe, expect, it, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/testUtils';
import { ExecutionEvidencePanel } from './ExecutionEvidencePanel';
import { ExecutionEvidenceSummary } from './ExecutionEvidenceSummary';
import { executionEvidence } from '@/lib/executionEvidence';
import type { ExecutionEvidence } from '@/types/workflow';

vi.mock('@/lib/executionEvidence', () => ({ executionEvidence: { get: vi.fn() } }));

function evidence(overrides: Partial<ExecutionEvidence> = {}): ExecutionEvidence {
  return {
    hasExecution: true,
    executionId: 'exec-1',
    executionStatus: 'RUNNING',
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
    automationStopped: false,
    stopReason: null,
    stoppedAtStage: null,
    failureCategory: null,
    timeline: [],
    ...overrides,
  };
}

describe('ExecutionEvidencePanel / ExecutionEvidenceSummary — P7 Action 7', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows an honest "no automation attempt" state when no execution exists — never a fake status', async () => {
    vi.mocked(executionEvidence.get).mockResolvedValue(evidence({ hasExecution: false, state: 'NOT_STARTED', automationStarted: false, instrumentationEnabled: null }));
    renderWithProviders(<ExecutionEvidencePanel applicationId="app-1" />);

    expect(await screen.findByText(/No automation execution has run for this application yet/)).toBeInTheDocument();
    expect(screen.queryByTestId('execution-evidence-summary')).not.toBeInTheDocument();
  });

  it('renders the CAPTCHA/login-stop status honestly: employer page reached, form not discovered', async () => {
    vi.mocked(executionEvidence.get).mockResolvedValue(
      evidence({
        state: 'EMPLOYER_PAGE_REACHED',
        employerPageReached: true,
        formDiscovered: false,
        captchaOrLoginDetected: true,
        automationStopped: true,
        stopReason: 'captcha or login wall detected — routed to human review',
      }),
    );
    renderWithProviders(<ExecutionEvidenceSummary applicationId="app-1" />);

    expect(await screen.findByText(/Automation reached the employer page/)).toBeInTheDocument();
    expect(screen.getByText(/Employer page: Reached/)).toBeInTheDocument();
    expect(screen.getByText(/Form discovered: Not reached/)).toBeInTheDocument();
    expect(screen.getByText(/captcha or login wall detected/)).toBeInTheDocument();
  });

  it('renders field-fill counts only when the backend actually recorded them', async () => {
    vi.mocked(executionEvidence.get).mockResolvedValue(
      evidence({ state: 'COMPLETED', employerPageReached: true, formDiscovered: true, fieldsFilled: 15, automationStopped: false }),
    );
    renderWithProviders(<ExecutionEvidenceSummary applicationId="app-1" />);

    expect(await screen.findByText(/Fields filled: 15/)).toBeInTheDocument();
  });

  it('never fabricates a fields-filled count — renders "Unknown" when the backend reports null', async () => {
    vi.mocked(executionEvidence.get).mockResolvedValue(evidence({ fieldsFilled: null }));
    renderWithProviders(<ExecutionEvidenceSummary applicationId="app-1" />);

    expect(await screen.findByText(/Fields filled: Unknown/)).toBeInTheDocument();
  });

  it('renders the stage timeline with real stage names and reasons', async () => {
    vi.mocked(executionEvidence.get).mockResolvedValue(
      evidence({
        timeline: [
          { sequence: 1, stage: 'NAVIGATION_STARTED', displayName: 'Navigation Started', status: 'COMPLETED' },
          { sequence: 2, stage: 'NAVIGATION_COMPLETED', displayName: 'Navigation Completed', status: 'COMPLETED' },
          { sequence: 3, stage: 'PAGE_CLASSIFIED', displayName: 'Page Classified', status: 'FAILED', reason: 'captcha or login wall detected' },
        ],
      }),
    );
    renderWithProviders(<ExecutionEvidencePanel applicationId="app-1" />);

    expect(await screen.findByText('Navigation Started')).toBeInTheDocument();
    expect(screen.getByText('Navigation Completed')).toBeInTheDocument();
    expect(screen.getByText('Page Classified')).toBeInTheDocument();
    expect(screen.getByText('captcha or login wall detected')).toBeInTheDocument();
  });

  it('technical details are collapsed by default and expand on click', async () => {
    const user = userEvent.setup();
    vi.mocked(executionEvidence.get).mockResolvedValue(evidence());
    renderWithProviders(<ExecutionEvidencePanel applicationId="app-1" />);

    await waitFor(() => expect(screen.getByTestId('execution-evidence-panel')).toBeInTheDocument());
    expect(screen.queryByText(/"executionId"/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Show technical details/ }));
    expect(screen.getByText(/"executionId"/)).toBeInTheDocument();
  });

  it('notes when detailed instrumentation is disabled rather than implying nothing happened', async () => {
    vi.mocked(executionEvidence.get).mockResolvedValue(
      evidence({ instrumentationEnabled: false, timeline: [], automationStarted: false, state: 'NOT_STARTED' }),
    );
    renderWithProviders(<ExecutionEvidenceSummary applicationId="app-1" />);

    expect(await screen.findByText(/Detailed stage instrumentation is off/)).toBeInTheDocument();
  });
});
