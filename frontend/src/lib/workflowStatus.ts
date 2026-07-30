import type { WorkflowAgent } from '@/types/workflow';
import type { BadgeTone } from '@/components/ui/badge';

/**
 * Phase 10E — single shared source of truth for AI-workflow-run status → tone/label.
 * Previously duplicated (with drifting labels) as `RUN_TONE` in Dashboard.tsx,
 * `RUN_STATUS` in Workflow.tsx, and `RUN_QUEUE_GROUP` in MissionDashboard.tsx.
 */
export const RUN_STATUS_TONE: Record<string, BadgeTone> = {
  COMPLETED: 'success',
  ERROR: 'danger',
  FAILED: 'danger',
  REJECTED: 'danger',
  RUNNING: 'info',
  IN_PROGRESS: 'info',
  INTERRUPTED: 'warning',
};

export const RUN_STATUS_LABEL: Record<string, string> = {
  COMPLETED: 'Completed',
  ERROR: 'Failed',
  FAILED: 'Failed',
  REJECTED: 'Rejected',
  RUNNING: 'Running',
  IN_PROGRESS: 'Running',
  INTERRUPTED: 'Needs approval',
};

export function runStatusTone(status: string): BadgeTone {
  return RUN_STATUS_TONE[status] ?? 'neutral';
}

export function runStatusLabel(status: string): string {
  return RUN_STATUS_LABEL[status] ?? status;
}

/**
 * "Where attention is owed" for a workflow run's agent timeline — a failure first,
 * then an active/awaiting stage, then the next pending one, else the last stage (so
 * a finished run reads as its final stage rather than "—"). Previously copy-pasted
 * in Workflow.tsx's PipelineOverview + RunCard and MissionDashboard.tsx's AiActivitySection.
 */
export function currentStage(agents: WorkflowAgent[]): WorkflowAgent | undefined {
  return (
    agents.find((a) => a.status === 'FAILED' || a.status === 'REJECTED') ??
    agents.find((a) => a.status === 'ACTIVE' || a.status === 'WAITING_FOR_APPROVAL') ??
    agents.find((a) => a.status === 'PENDING') ??
    agents[agents.length - 1]
  );
}

/**
 * Compact human label for a millisecond duration (e.g. "1.4s", "820ms", "2m 5s").
 * Previously duplicated in Workflow.tsx, WorkflowInsights.tsx, and
 * WorkflowStatusStepper.tsx — the latter's copy was missing the minutes branch,
 * so a stage taking over a minute (a real occurrence — see CLAUDE.md's ~74s
 * deepseek cold-start note) rendered as raw seconds there but "Xm Ys" everywhere
 * else on the same page. Consolidating onto this (the fuller) implementation is
 * a display-only fix, not a data/logic change.
 */
export function formatWorkflowDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.round((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}
