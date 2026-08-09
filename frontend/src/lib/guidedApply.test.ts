import { describe, expect, it, vi, beforeEach } from 'vitest';
import { guidedApply } from './guidedApply';
import { api } from './api';

vi.mock('./api', () => ({ api: { get: vi.fn() } }));

describe('guidedApply.brief', () => {
  beforeEach(() => vi.clearAllMocks());

  it('requests the correct application-scoped URL', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { profile: [], recommendedAnswers: [] } });

    await guidedApply.brief('app-123');

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith('/api/applications/app-123/guided-apply-brief');
  });

  it('returns the response data unchanged', async () => {
    const payload = {
      candidateName: 'Ada Lovelace',
      candidateEmail: 'ada@example.com',
      resumeFilename: 'resume.pdf',
      profile: [{ label: 'Name', value: 'Ada Lovelace', source: 'User.fullName' }],
      recommendedAnswers: [],
    };
    vi.mocked(api.get).mockResolvedValue({ data: payload });

    const result = await guidedApply.brief('app-123');

    expect(result).toEqual(payload);
  });

  it('degrades to null on request failure rather than throwing (read path, dark-flag convention)', async () => {
    vi.mocked(api.get).mockRejectedValue(new Error('network error'));

    await expect(guidedApply.brief('app-123')).resolves.toBeNull();
  });
});
