import { AlertTriangle, Check, Loader2, XCircle } from 'lucide-react';
import { cn } from '@/lib/cn';
import type { ApplicationSubmissionSession } from '@/types/workflow';

/**
 * Phase 7.16 — the 13 raw session states grouped into 6 readable phases, mirroring the visual
 * language of the LangGraph WorkflowStatusStepper (green completed circles + connectors, amber
 * pulse for human approval, spinner for the active phase). Purpose-built rather than reusing
 * WorkflowStatusStepper, which is bound to the LangGraph workflow's own hook + agent model.
 */
const PHASES: { label: string; statuses: string[] }[] = [
  { label: 'Validate', statuses: ['CREATED', 'VALIDATING'] },
  { label: 'Prepare package', statuses: ['PACKAGE_READY', 'REVIEW_READY', 'COMPANY_READY', 'STAR_READY'] },
  { label: 'Ready', statuses: ['READY_FOR_SUBMISSION'] },
  { label: 'Human approval', statuses: ['WAITING_APPROVAL'] },
  { label: 'Submit', statuses: ['SUBMITTING', 'SUBMITTED', 'VERIFIED', 'VERIFICATION_FAILED', 'WAITING_MANUAL_SUBMISSION'] },
  { label: 'Complete', statuses: ['TRACKING', 'COMPLETED'] },
];

/** The furthest phase a FAILED session reached, inferred from which artifacts/ids it managed to set. */
function reachedPhaseOnFailure(s: ApplicationSubmissionSession): number {
  if (s.applicationExecutionId) return 4; // got into Submit
  if (s.approvalQueueEntryId) return 3; // reached Human approval
  if (s.applicationReviewId || s.applicationPackageId) return 1; // reached Prepare
  return 0; // failed during Validate
}

type PhaseState = 'done' | 'active' | 'approval' | 'failed' | 'pending';

export function SubmissionStepper({ session }: { session: ApplicationSubmissionSession }) {
  const status = session.status;
  const failed = status === 'FAILED';
  const completed = status === 'COMPLETED';

  const currentIndex = failed
    ? reachedPhaseOnFailure(session)
    : PHASES.findIndex((p) => p.statuses.includes(status));
  const activeIndex = currentIndex < 0 ? 0 : currentIndex;

  function phaseState(i: number): PhaseState {
    if (completed) return 'done';
    if (i < activeIndex) return 'done';
    if (i > activeIndex) return 'pending';
    // i === activeIndex
    if (failed) return 'failed';
    // WAITING_MANUAL_SUBMISSION means no automated submission actually happened — never render it
    // as a spinning "in progress" step, which would imply completion is imminent on its own.
    if (status === 'WAITING_APPROVAL' || status === 'WAITING_MANUAL_SUBMISSION') return 'approval';
    return 'active';
  }

  const doneCount = PHASES.filter((_, i) => phaseState(i) === 'done').length;

  return (
    <div className="w-full">
      <div className="mb-2 flex items-center justify-between text-xs text-muted-foreground">
        <span className="font-semibold uppercase tracking-wide">Pipeline</span>
        <span>{completed ? '6 / 6 stages' : `${doneCount} / ${PHASES.length} stages`}</span>
      </div>
      <div className="flex items-start">
        {PHASES.map((phase, i) => {
          const st = phaseState(i);
          const isLast = i === PHASES.length - 1;
          const connectorDone = phaseState(i) === 'done';
          return (
            <div key={phase.label} className="flex flex-1 flex-col items-center">
              <div className="flex w-full items-center">
                {/* left half-connector (invisible on first) */}
                <div
                  className={cn(
                    'h-0.5 flex-1',
                    i === 0 ? 'opacity-0' : phaseState(i - 1) === 'done' ? 'bg-success/60' : 'bg-border',
                  )}
                />
                <StepCircle state={st} />
                {/* right half-connector (invisible on last) */}
                <div
                  className={cn(
                    'h-0.5 flex-1',
                    isLast ? 'opacity-0' : connectorDone ? 'bg-success/60' : 'bg-border',
                  )}
                />
              </div>
              <span
                className={cn(
                  'mt-1.5 px-0.5 text-center text-[10px] font-medium leading-tight',
                  st === 'pending' ? 'text-muted-foreground' : 'text-foreground',
                )}
              >
                {phase.label}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function StepCircle({ state }: { state: PhaseState }) {
  const cfg: Record<PhaseState, { ring: string; bg: string; icon: string }> = {
    done: { ring: 'border-success/40 ring-success/20', bg: 'bg-success/10', icon: 'text-success' },
    active: { ring: 'border-secondary/50 ring-secondary/25', bg: 'bg-secondary/10', icon: 'text-secondary' },
    approval: { ring: 'border-warning/50 ring-warning/25', bg: 'bg-warning/10', icon: 'text-warning' },
    failed: { ring: 'border-danger/40 ring-danger/20', bg: 'bg-danger/10', icon: 'text-danger' },
    pending: { ring: 'border-border ring-transparent', bg: 'bg-muted', icon: 'text-muted-foreground' },
  };
  const c = cfg[state];
  return (
    <div
      className={cn(
        'relative flex h-8 w-8 shrink-0 items-center justify-center rounded-full border ring-4',
        c.bg,
        c.ring,
      )}
    >
      {state === 'approval' && (
        <span className="absolute inset-0 animate-pulse rounded-full border-2 border-warning/60" />
      )}
      {state === 'active' ? (
        <Loader2 className={cn('h-4 w-4 animate-spin', c.icon)} />
      ) : state === 'failed' ? (
        <XCircle className={cn('h-4 w-4', c.icon)} />
      ) : state === 'approval' ? (
        <AlertTriangle className={cn('h-4 w-4', c.icon)} />
      ) : state === 'done' ? (
        <Check className={cn('h-4 w-4', c.icon)} />
      ) : (
        <span className={cn('h-2 w-2 rounded-full', 'bg-current', c.icon)} />
      )}
    </div>
  );
}
