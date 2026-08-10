import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SubmissionStepper } from './SubmissionStepper';
import type { ApplicationSubmissionSession } from '@/types/workflow';

function session(overrides: Partial<ApplicationSubmissionSession> = {}): ApplicationSubmissionSession {
  return {
    id: 's1', userId: 'u1', jobId: 'j1', status: 'READY_FOR_SUBMISSION',
    submissionMethod: 'MANUAL', createdAt: '2026-08-10T00:00:00Z', updatedAt: '2026-08-10T00:00:00Z',
    ...overrides,
  };
}

describe('SubmissionStepper — artifact reuse disclosure', () => {
  it('shows nothing when answersReuseDecision is absent (rows predating this field)', () => {
    render(<SubmissionStepper session={session({ answersReuseDecision: null })} />);
    expect(screen.queryByText(/reused answers/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/regenerated/i)).not.toBeInTheDocument();
  });

  it('shows nothing on a first-time FULL_BUILD — no history to disclose', () => {
    render(<SubmissionStepper session={session({ answersReuseDecision: 'FULL_BUILD' })} />);
    expect(screen.queryByText(/reused answers/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/regenerated/i)).not.toBeInTheDocument();
  });

  it('discloses reuse honestly when answers were reused — no AI calls claim', () => {
    render(<SubmissionStepper session={session({ answersReuseDecision: 'REUSED' })} />);
    expect(screen.getByText(/reused answers from a previous application/i)).toBeInTheDocument();
  });

  it('discloses the rebuild reason when resume changed', () => {
    render(<SubmissionStepper session={session({ answersReuseDecision: 'REBUILT_RESUME_CHANGED' })} />);
    expect(screen.getByText(/answers regenerated \(resume changed\)/i)).toBeInTheDocument();
  });

  it('discloses the rebuild reason when the application package changed', () => {
    render(<SubmissionStepper session={session({ answersReuseDecision: 'REBUILT_PACKAGE_CHANGED' })} />);
    expect(screen.getByText(/application package changed/i)).toBeInTheDocument();
  });
});
