import { AlertTriangle, Check, Loader2, XCircle } from 'lucide-react';
import { cn } from '@/lib/cn';
import type { LifecycleView } from '@/types/workflow';

/**
 * Application Command Center — horizontal lifecycle stepper. Visually modeled on
 * `components/submission/SubmissionStepper.tsx` (green-check circles, connector fill, pulse for
 * the active step), but driven by the real Phase 3A 16-status lifecycle
 * (`ai.careerpilot.domain.ApplicationLifecycle`) instead of submission-session phases. The 16
 * statuses map close to 1:1 onto stages here — no fabricated stages.
 */
const STAGES: { label: string; statuses: string[] }[] = [
  { label: 'Draft', statuses: ['DRAFT'] },
  { label: 'Submitted', statuses: ['SUBMITTED'] },
  { label: 'Viewed', statuses: ['VIEWED'] },
  { label: 'Under review', statuses: ['UNDER_REVIEW'] },
  { label: 'Assessment', statuses: ['ASSESSMENT'] },
  { label: 'Interviews', statuses: ['TECHNICAL_INTERVIEW', 'SYSTEM_DESIGN', 'MANAGER_INTERVIEW', 'HR_INTERVIEW'] },
  { label: 'Final round', statuses: ['FINAL_ROUND'] },
  { label: 'Offer', statuses: ['OFFER_RECEIVED', 'NEGOTIATION'] },
  { label: 'Accepted', statuses: ['ACCEPTED'] },
];

type StepState = 'done' | 'active' | 'terminal-negative' | 'pending';

export function ApplicationTimelineStepper({ lifecycle }: { lifecycle: LifecycleView | null | undefined }) {
  const status = lifecycle?.lifecycle?.currentStatus;
  const terminalNegative = status === 'REJECTED' || status === 'WITHDRAWN' || status === 'EXPIRED';

  if (!status) {
    return (
      <p className="rounded-lg border border-dashed border-border bg-muted/20 p-4 text-sm text-muted-foreground">
        No lifecycle tracking yet for this application.
      </p>
    );
  }

  const activeIndex = terminalNegative
    ? STAGES.length - 1
    : Math.max(0, STAGES.findIndex((s) => s.statuses.includes(status)));

  function stateFor(i: number): StepState {
    if (terminalNegative) return i < activeIndex ? 'done' : i === activeIndex ? 'terminal-negative' : 'pending';
    if (i < activeIndex) return 'done';
    if (i > activeIndex) return 'pending';
    return status === 'ACCEPTED' ? 'done' : 'active';
  }

  return (
    <div className="w-full">
      <div className="mb-2 flex items-center justify-between text-xs text-muted-foreground">
        <span className="font-semibold uppercase tracking-wide">Lifecycle</span>
        <span>{terminalNegative ? status : `Stage ${activeIndex + 1} / ${STAGES.length}`}</span>
      </div>
      <div className="flex items-start overflow-x-auto pb-1">
        {STAGES.map((stage, i) => {
          const st = stateFor(i);
          const isLast = i === STAGES.length - 1;
          return (
            <div key={stage.label} className="flex min-w-[4.5rem] flex-1 flex-col items-center">
              <div className="flex w-full items-center">
                <div className={cn('h-0.5 flex-1', i === 0 ? 'opacity-0' : stateFor(i - 1) === 'done' ? 'bg-success/60' : 'bg-border')} />
                <StepCircle state={st} />
                <div className={cn('h-0.5 flex-1', isLast ? 'opacity-0' : st === 'done' ? 'bg-success/60' : 'bg-border')} />
              </div>
              <span className={cn('mt-1.5 px-0.5 text-center text-[10px] font-medium leading-tight', st === 'pending' ? 'text-muted-foreground' : 'text-foreground')}>
                {stage.label}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function StepCircle({ state }: { state: StepState }) {
  const cfg: Record<StepState, { ring: string; bg: string; icon: string }> = {
    done: { ring: 'border-success/40 ring-success/20', bg: 'bg-success/10', icon: 'text-success' },
    active: { ring: 'border-secondary/50 ring-secondary/25', bg: 'bg-secondary/10', icon: 'text-secondary' },
    'terminal-negative': { ring: 'border-danger/40 ring-danger/20', bg: 'bg-danger/10', icon: 'text-danger' },
    pending: { ring: 'border-border ring-transparent', bg: 'bg-muted', icon: 'text-muted-foreground' },
  };
  const c = cfg[state];
  return (
    <div className={cn('relative flex h-7 w-7 shrink-0 items-center justify-center rounded-full border ring-4', c.bg, c.ring)}>
      {state === 'active' ? (
        <Loader2 className={cn('h-3.5 w-3.5 animate-spin', c.icon)} />
      ) : state === 'terminal-negative' ? (
        <XCircle className={cn('h-3.5 w-3.5', c.icon)} />
      ) : state === 'done' ? (
        <Check className={cn('h-3.5 w-3.5', c.icon)} />
      ) : (
        <span className={cn('h-1.5 w-1.5 rounded-full bg-current', c.icon)} />
      )}
    </div>
  );
}

/** Small helper re-exported for callers that just want an icon for "no data" states. */
export function TimelineUnavailableIcon() {
  return <AlertTriangle className="h-4 w-4 text-muted-foreground" />;
}
