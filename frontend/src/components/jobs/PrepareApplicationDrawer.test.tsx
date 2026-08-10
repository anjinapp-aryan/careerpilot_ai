import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PrepareApplicationDrawer } from './PrepareApplicationDrawer';
import type { RecommendedJob } from '@/types/workflow';

function rec(overrides: Partial<RecommendedJob> = {}): RecommendedJob {
  return {
    job: {
      id: 'job-1',
      title: 'Senior Backend Engineer',
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

describe('PrepareApplicationDrawer — never a duplicate submission path', () => {
  it('renders nothing (dialog closed) when rec is null, and calls neither callback', () => {
    const onApply = vi.fn();
    const onClose = vi.fn();
    render(<PrepareApplicationDrawer rec={null} onClose={onClose} onApply={onApply} />);
    expect(onApply).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('opening the drawer never calls onApply on its own', () => {
    const onApply = vi.fn();
    render(<PrepareApplicationDrawer rec={rec()} onClose={vi.fn()} onApply={onApply} />);
    expect(onApply).not.toHaveBeenCalled();
  });

  it('shows the correct job title and company for the selected recommendation', () => {
    render(<PrepareApplicationDrawer rec={rec()} onClose={vi.fn()} onApply={vi.fn()} />);
    expect(screen.getByText(/senior backend engineer.*gitlab/i)).toBeInTheDocument();
  });

  it('renders matched and missing skills from the existing recommendation data, nothing recomputed', () => {
    render(<PrepareApplicationDrawer rec={rec()} onClose={vi.fn()} onApply={vi.fn()} />);
    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('Kubernetes')).toBeInTheDocument();
  });

  it('shows the real employer URL, never fabricated, opened externally', () => {
    render(<PrepareApplicationDrawer rec={rec()} onClose={vi.fn()} onApply={vi.fn()} />);
    const link = screen.getByRole('link', { name: /view employer job/i });
    expect(link).toHaveAttribute('href', 'https://job-boards.greenhouse.io/gitlab/jobs/8646556002');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('shows "Employer posting unavailable" instead of a broken link when no URL was captured', () => {
    render(
      <PrepareApplicationDrawer
        rec={rec({ job: { ...rec().job, sourceUrl: null, externalUrl: null } })}
        onClose={vi.fn()}
        onApply={vi.fn()}
      />,
    );
    expect(screen.getByText(/employer posting unavailable/i)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /view employer job/i })).not.toBeInTheDocument();
  });

  it('"Apply now" calls onApply with the job id — the only path from this drawer to the existing pipeline', () => {
    const onApply = vi.fn();
    render(<PrepareApplicationDrawer rec={rec()} onClose={vi.fn()} onApply={onApply} />);
    screen.getByRole('button', { name: /apply now/i }).click();
    expect(onApply).toHaveBeenCalledWith('job-1');
    expect(onApply).toHaveBeenCalledTimes(1);
  });

  it('"Cancel" calls onClose, never onApply', () => {
    const onApply = vi.fn();
    const onClose = vi.fn();
    render(<PrepareApplicationDrawer rec={rec()} onClose={onClose} onApply={onApply} />);
    screen.getByRole('button', { name: /^cancel$/i }).click();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onApply).not.toHaveBeenCalled();
  });
});
