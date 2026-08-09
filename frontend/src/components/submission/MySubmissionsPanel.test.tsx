import { describe, expect, it, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/testUtils';
import { MySubmissionsPanel } from './MySubmissionsPanel';
import { applicationSubmission } from '@/lib/applicationSubmission';
import type { ApplicationSubmissionSession } from '@/types/workflow';

vi.mock('@/lib/applicationSubmission', () => ({
  applicationSubmission: {
    list: vi.fn(),
    get: vi.fn(),
    reportSubmitted: vi.fn(),
  },
}));

function session(overrides: Partial<ApplicationSubmissionSession> = {}): ApplicationSubmissionSession {
  return {
    id: 'session-1',
    userId: 'user-1',
    jobId: 'job-1',
    status: 'WAITING_MANUAL_SUBMISSION',
    submissionMethod: 'MANUAL',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

async function openRow() {
  const user = userEvent.setup();
  await user.click(screen.getByText(/job #/i));
  return user;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(applicationSubmission.get).mockResolvedValue({ session: session(), answers: [] });
});

describe('MySubmissionsPanel — manual submission dialog (Test K)', () => {
  it('shows the confirm dialog and does NOT call report-submitted on "Not yet"', async () => {
    vi.mocked(applicationSubmission.list).mockResolvedValue([session()]);
    renderWithProviders(<MySubmissionsPanel />);
    await screen.findByText(/job #/i);
    const user = await openRow();

    await user.click(screen.getByRole('button', { name: /mark as submitted/i }));
    expect(await screen.findByText('Did you submit this application?')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /not yet/i }));

    await waitFor(() => expect(screen.queryByText('Did you submit this application?')).not.toBeInTheDocument());
    expect(applicationSubmission.reportSubmitted).not.toHaveBeenCalled();
  });
});

describe('MySubmissionsPanel — manual submission confirmation (Test L)', () => {
  it('calls report-submitted exactly once with the note entered, verbatim', async () => {
    vi.mocked(applicationSubmission.list).mockResolvedValue([session()]);
    vi.mocked(applicationSubmission.reportSubmitted).mockResolvedValue(
      session({ status: 'USER_REPORTED_SUBMITTED' }),
    );
    renderWithProviders(<MySubmissionsPanel />);
    await screen.findByText(/job #/i);
    const user = await openRow();

    await user.click(screen.getByRole('button', { name: /mark as submitted/i }));
    await screen.findByText('Did you submit this application?');
    await user.type(screen.getByPlaceholderText(/optional note/i), 'Submitted through Greenhouse.');
    await user.click(screen.getByRole('button', { name: /yes, i submitted it/i }));

    await waitFor(() => expect(applicationSubmission.reportSubmitted).toHaveBeenCalledTimes(1));
    expect(applicationSubmission.reportSubmitted).toHaveBeenCalledWith('session-1', 'Submitted through Greenhouse.');
  });
});

describe('MySubmissionsPanel — double-click safety (Test M)', () => {
  it('does not fire a second mutation while the first is still pending', async () => {
    vi.mocked(applicationSubmission.list).mockResolvedValue([session()]);
    let resolveIt!: (v: ApplicationSubmissionSession) => void;
    vi.mocked(applicationSubmission.reportSubmitted).mockReturnValue(
      new Promise((resolve) => {
        resolveIt = resolve;
      }),
    );
    renderWithProviders(<MySubmissionsPanel />);
    await screen.findByText(/job #/i);
    const user = await openRow();
    await user.click(screen.getByRole('button', { name: /mark as submitted/i }));
    await screen.findByText('Did you submit this application?');

    const confirmBtn = screen.getByRole('button', { name: /yes, i submitted it/i });
    await user.click(confirmBtn);
    // The button becomes disabled (existing Button `loading` convention) while the mutation is
    // in flight — a rapid second click must not reach the handler.
    await user.click(confirmBtn);
    await user.click(confirmBtn);

    resolveIt(session({ status: 'USER_REPORTED_SUBMITTED' }));
    await waitFor(() => expect(applicationSubmission.reportSubmitted).toHaveBeenCalledTimes(1));
  });
});

describe('MySubmissionsPanel — 409 handling (Test N)', () => {
  it('shows a truthful error and leaves the session state unchanged, never a false success', async () => {
    vi.mocked(applicationSubmission.list).mockResolvedValue([session()]);
    const conflict = Object.assign(new Error('Conflict'), { response: { status: 409 } });
    vi.mocked(applicationSubmission.reportSubmitted).mockRejectedValue(conflict);

    renderWithProviders(<MySubmissionsPanel />);
    await screen.findByText(/job #/i);
    const user = await openRow();
    await user.click(screen.getByRole('button', { name: /mark as submitted/i }));
    await screen.findByText('Did you submit this application?');
    await user.click(screen.getByRole('button', { name: /yes, i submitted it/i }));

    expect(await screen.findByText(/could not update/i)).toBeInTheDocument();
    // No false success: still shows the original WAITING_MANUAL_SUBMISSION explanation, never the
    // "you reported this as submitted" copy.
    expect(screen.queryByText(/you reported this application as submitted/i)).not.toBeInTheDocument();
    expect(screen.getByText(/automated submission isn't available/i)).toBeInTheDocument();
  });
});

describe('MySubmissionsPanel — report-submitted 500 failure (Test O)', () => {
  it('shows a generic failure message and allows retry — no duplicate mutation, no false state', async () => {
    vi.mocked(applicationSubmission.list).mockResolvedValue([session()]);
    const serverError = Object.assign(new Error('Server error'), { response: { status: 500 } });
    vi.mocked(applicationSubmission.reportSubmitted).mockRejectedValueOnce(serverError);

    renderWithProviders(<MySubmissionsPanel />);
    await screen.findByText(/job #/i);
    const user = await openRow();
    await user.click(screen.getByRole('button', { name: /mark as submitted/i }));
    await screen.findByText('Did you submit this application?');
    await user.click(screen.getByRole('button', { name: /yes, i submitted it/i }));

    expect(await screen.findByText(/could not update/i)).toBeInTheDocument();
    expect(applicationSubmission.reportSubmitted).toHaveBeenCalledTimes(1);

    // Retry succeeds.
    vi.mocked(applicationSubmission.reportSubmitted).mockResolvedValueOnce(
      session({ status: 'USER_REPORTED_SUBMITTED' }),
    );
    await user.click(screen.getByRole('button', { name: /yes, i submitted it/i }));
    await waitFor(() => expect(applicationSubmission.reportSubmitted).toHaveBeenCalledTimes(2));
  });
});

describe('MySubmissionsPanel — already reported submitted (Test P)', () => {
  it('shows the honest not-verified-by-CareerPilot copy and hides the manual-submit control', async () => {
    vi.mocked(applicationSubmission.list).mockResolvedValue([
      session({
        status: 'USER_REPORTED_SUBMITTED',
        userReportedSubmittedAt: '2026-01-02T10:00:00Z',
        userSubmissionNote: 'Done via portal',
      }),
    ]);
    renderWithProviders(<MySubmissionsPanel />);
    await screen.findByText(/job #/i);
    await openRow();

    expect(await screen.findByText(/you reported this application as submitted/i)).toBeInTheDocument();
    expect(screen.getByText(/careerpilot did not verify this itself/i)).toBeInTheDocument();
    expect(screen.getByText(/done via portal/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /mark as submitted/i })).not.toBeInTheDocument();
  });
});

describe('MySubmissionsPanel — system submission statuses stay semantically distinct (Test Q)', () => {
  it('SUBMITTED does not show the manual-submission controls', async () => {
    vi.mocked(applicationSubmission.list).mockResolvedValue([session({ status: 'SUBMITTED' })]);
    renderWithProviders(<MySubmissionsPanel />);
    await screen.findByText(/job #/i);
    await openRow();

    expect(screen.queryByRole('button', { name: /mark as submitted/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/you reported this application as submitted/i)).not.toBeInTheDocument();
  });

  it('SUBMIT_UNVERIFIED is never rendered as USER_REPORTED_SUBMITTED', async () => {
    vi.mocked(applicationSubmission.list).mockResolvedValue([session({ status: 'SUBMIT_UNVERIFIED' })]);
    renderWithProviders(<MySubmissionsPanel />);

    expect(await screen.findByText(/submit unverified/i)).toBeInTheDocument();
    expect(screen.queryByText(/you reported this application as submitted/i)).not.toBeInTheDocument();
  });
});
