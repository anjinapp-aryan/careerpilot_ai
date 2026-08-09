import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DndContext } from '@dnd-kit/core';
import { ApplicationCardV2 } from './ApplicationCardV2';
import type { ApplicationCard } from '@/types/workflow';

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
    guidedApplyRequired: false,
    jobTitle: 'Senior Java Backend Engineer',
    company: 'Acme Technologies',
    ...overrides,
  };
}

function renderCard(c: ApplicationCard) {
  return render(
    <DndContext onDragEnd={() => {}}>
      <ApplicationCardV2 card={c} onDetails={vi.fn()} />
    </DndContext>,
  );
}

describe('ApplicationCardV2 — Guided Apply badge (Test A)', () => {
  it('shows the Guided Apply badge and warning line when guidedApplyRequired is true', () => {
    renderCard(card({ guidedApplyRequired: true }));

    expect(screen.getByText(/guided apply/i)).toBeInTheDocument();
    expect(screen.getByText(/manual completion required/i)).toBeInTheDocument();
  });
});

describe('ApplicationCardV2 — normal application regression (Test B)', () => {
  it('never shows Guided Apply UI for a normal application', () => {
    renderCard(card({ guidedApplyRequired: false }));

    expect(screen.queryByText(/guided apply/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/manual completion required/i)).not.toBeInTheDocument();
  });

  it('still renders the normal card content (job title, company)', () => {
    renderCard(card({ guidedApplyRequired: false }));

    expect(screen.getByText('Senior Java Backend Engineer')).toBeInTheDocument();
    expect(screen.getByText('Acme Technologies')).toBeInTheDocument();
  });
});
