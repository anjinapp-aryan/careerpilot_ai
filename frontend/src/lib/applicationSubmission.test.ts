import { describe, expect, it, vi, beforeEach } from 'vitest';
import { applicationSubmission } from './applicationSubmission';
import { api } from './api';

vi.mock('./api', () => ({ api: { post: vi.fn() } }));

describe('applicationSubmission.reportSubmitted', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts to the exact session-scoped report-submitted endpoint', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'session-1', status: 'USER_REPORTED_SUBMITTED' } });

    await applicationSubmission.reportSubmitted('session-1');

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post).toHaveBeenCalledWith('/api/application-submission/session-1/report-submitted', { note: null });
  });

  it('sends the note exactly as entered — no client-side rewriting', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'session-1', status: 'USER_REPORTED_SUBMITTED' } });

    await applicationSubmission.reportSubmitted('session-1', 'Submitted through Greenhouse.');

    expect(api.post).toHaveBeenCalledWith('/api/application-submission/session-1/report-submitted', {
      note: 'Submitted through Greenhouse.',
    });
  });

  it('never fabricates a client-side timestamp — the resolved value is exactly the server response', async () => {
    const serverResponse = {
      id: 'session-1',
      status: 'USER_REPORTED_SUBMITTED',
      userReportedSubmittedAt: '2026-08-08T10:00:00Z',
    };
    vi.mocked(api.post).mockResolvedValue({ data: serverResponse });

    const result = await applicationSubmission.reportSubmitted('session-1');

    expect(result).toEqual(serverResponse);
  });

  /**
   * Test N/O — unlike the read methods in this module (which swallow errors and return null/[]),
   * reportSubmitted must propagate a failure so the UI can react truthfully: a 409 (wrong session
   * state) or a 500 must never be silently absorbed into an apparent success.
   */
  it('propagates a 409 rather than swallowing it', async () => {
    const error = Object.assign(new Error('Conflict'), { response: { status: 409 } });
    vi.mocked(api.post).mockRejectedValue(error);

    await expect(applicationSubmission.reportSubmitted('session-1')).rejects.toBe(error);
  });

  it('propagates a 500 rather than swallowing it', async () => {
    const error = Object.assign(new Error('Server error'), { response: { status: 500 } });
    vi.mocked(api.post).mockRejectedValue(error);

    await expect(applicationSubmission.reportSubmitted('session-1')).rejects.toBe(error);
  });
});
