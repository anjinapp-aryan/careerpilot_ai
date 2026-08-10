import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RecommendedJobCard } from './RecommendedJobs';
import type { RecommendedJob } from '@/types/workflow';

function rec(overrides: Partial<RecommendedJob> = {}): RecommendedJob {
  return {
    job: {
      id: 'job-1',
      title: 'Senior Backend Engineer, Architecture Engineering',
      company: 'GitLab',
      location: 'Remote',
      source: 'greenhouse',
      sourceUrl: 'https://job-boards.greenhouse.io/gitlab/jobs/8646556002',
      externalUrl: null,
      createdAt: '2026-08-01T00:00:00Z',
    },
    matchScore: 94,
    matchedSkills: ['Java', 'Spring Boot'],
    missingSkills: ['Kubernetes'],
    atsPlatform: 'GREENHOUSE',
    ...overrides,
  };
}

function renderCard(
  r: RecommendedJob,
  { onApply = vi.fn(), onSave = vi.fn(), onPrepare = vi.fn() as (() => void) | undefined } = {},
) {
  return {
    onApply,
    onSave,
    onPrepare,
    ...render(
      <RecommendedJobCard
        rec={r}
        index={0}
        onApply={onApply}
        onSave={onSave}
        onExplain={vi.fn()}
        onRelevance={vi.fn()}
        onPrepare={onPrepare}
        busy={false}
      />,
    ),
  };
}

describe('RecommendedJobCard — employer identity (trust anchor)', () => {
  it('renders the real company name', () => {
    renderCard(rec());
    expect(screen.getByText('GitLab')).toBeInTheDocument();
  });

  it('shows a "View Employer Job" link pointing at the real captured sourceUrl, opened externally', () => {
    renderCard(rec());
    const link = screen.getByRole('link', { name: /view employer job/i });
    expect(link).toHaveAttribute('href', 'https://job-boards.greenhouse.io/gitlab/jobs/8646556002');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('prefers sourceUrl over externalUrl (same precedence as GuestApplyAutomationService#applyUrl)', () => {
    renderCard(rec({
      job: { ...rec().job, sourceUrl: 'https://job-boards.greenhouse.io/gitlab/jobs/1', externalUrl: 'https://other.example/x' },
    }), {});
    expect(screen.getByRole('link', { name: /view employer job/i })).toHaveAttribute(
      'href', 'https://job-boards.greenhouse.io/gitlab/jobs/1',
    );
  });

  it('never fabricates a URL — shows "Employer posting unavailable" instead of a broken link when none was captured', () => {
    renderCard(rec({ job: { ...rec().job, sourceUrl: null, externalUrl: null } }));
    expect(screen.getByText(/employer posting unavailable/i)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /view employer job/i })).not.toBeInTheDocument();
  });

  it('shows the ATS badge only when genuinely detected, never a guess', () => {
    renderCard(rec({ atsPlatform: 'GREENHOUSE' }));
    expect(screen.getByText(/ats: greenhouse/i)).toBeInTheDocument();
  });

  it('omits the ATS badge entirely when the platform is unrecognised (atsPlatform absent)', () => {
    renderCard(rec({ atsPlatform: null }));
    expect(screen.queryByText(/ats:/i)).not.toBeInTheDocument();
    // Source line (company Careers) still renders — the omission is scoped to ATS only.
    expect(screen.getByText(/source: gitlab careers/i)).toBeInTheDocument();
  });
});

describe('RecommendedJobCard — Apply regression (must remain the existing, unmodified entry point)', () => {
  it('"Apply" calls onApply directly with the job id — unchanged from before Prepare Application existed', () => {
    const { onApply, onPrepare } = renderCard(rec());
    screen.getByRole('button', { name: /^apply$/i }).click();
    expect(onApply).toHaveBeenCalledWith('job-1');
    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onPrepare).not.toHaveBeenCalled();
  });

  it('"Save" still calls onSave with the job id', () => {
    const { onSave } = renderCard(rec());
    screen.getByRole('button', { name: /^save$/i }).click();
    expect(onSave).toHaveBeenCalledWith('job-1');
  });
});

describe('RecommendedJobCard — Prepare Application (separate, read-only, never auto-applies)', () => {
  it('"Prepare Application" calls onPrepare, never onApply', () => {
    const { onApply, onPrepare } = renderCard(rec());
    screen.getByRole('button', { name: /prepare application/i }).click();
    expect(onPrepare).toHaveBeenCalledTimes(1);
    expect(onApply).not.toHaveBeenCalled();
  });

  it('is omitted entirely when the caller does not provide onPrepare', () => {
    // Renders RecommendedJobCard directly (bypassing renderCard's defaulting helper, since a JS
    // default parameter fires even for an explicitly-passed `undefined`) to prove the real
    // no-onPrepare-prop case, e.g. a caller upstream that hasn't wired the drawer yet.
    render(
      <RecommendedJobCard rec={rec()} index={0} onApply={vi.fn()} onSave={vi.fn()} onExplain={vi.fn()} onRelevance={vi.fn()} busy={false} />,
    );
    expect(screen.queryByRole('button', { name: /prepare application/i })).not.toBeInTheDocument();
    // Apply and Save remain unaffected by onPrepare's absence.
    expect(screen.getByRole('button', { name: /^apply$/i })).toBeInTheDocument();
  });
});

describe('RecommendedJobCard — match information', () => {
  it('renders the match score and matched/missing skills', () => {
    renderCard(rec());
    expect(screen.getByText(/match score: 94%/i)).toBeInTheDocument();
    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('Kubernetes')).toBeInTheDocument();
  });
});
