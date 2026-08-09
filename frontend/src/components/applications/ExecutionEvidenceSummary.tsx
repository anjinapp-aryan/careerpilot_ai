import { useQuery } from '@tanstack/react-query';
import { executionEvidence } from '@/lib/executionEvidence';
import type { ExecutionEvidence } from '@/types/workflow';

/** Category A/B/C truth-model boundary (P7 Action 7 mission): this file renders ONLY automation
 *  execution evidence (category A) — never Guided Apply readiness (category B, rendered by
 *  `GuidedApplyBriefPanel` itself) or human-action facts (category C, `MySubmissionsPanel`). */

const STATUS_ICON: Record<string, string> = {
  NOT_STARTED: '⚪',
  STARTED: '🟡',
  EMPLOYER_PAGE_REACHED: '🟡',
  FORM_DISCOVERED: '🟡',
  FILLING: '🟡',
  PARTIALLY_FILLED: '🟡',
  STOPPED: '🟡',
  COMPLETED: '🟢',
  UNKNOWN: '⚪',
};

const STATUS_LABEL: Record<string, string> = {
  NOT_STARTED: 'Automation not started',
  STARTED: 'Automation started',
  EMPLOYER_PAGE_REACHED: 'Automation reached the employer page',
  FORM_DISCOVERED: 'Automation found the application form',
  FILLING: 'Automation was filling the form',
  PARTIALLY_FILLED: 'Automation partially filled the form, then stopped',
  STOPPED: 'Automation stopped',
  COMPLETED: 'Automation completed',
  UNKNOWN: 'Automation status unknown',
};

/** A genuine defect (browser/network/upload/submit/verification/infra) reads 🔴; a safe, expected
 *  boundary (CAPTCHA/login/unsupported-control/missing-data) reads 🟡 — same "never false alarm,
 *  never false success" discipline as the rest of this feature. */
const FAILED_CATEGORIES = new Set(['BROWSER', 'NAVIGATION', 'UPLOAD', 'FIELD_FILL', 'SUBMIT', 'VERIFICATION', 'PERSISTENCE', 'INFRASTRUCTURE']);

function statusIcon(ev: ExecutionEvidence): string {
  if (ev.automationStopped && ev.failureCategory && FAILED_CATEGORIES.has(ev.failureCategory)) return '🔴';
  return STATUS_ICON[ev.state] ?? '⚪';
}

function fact(label: string, value: boolean | null | undefined): string {
  if (value === null || value === undefined) return `${label}: Unknown`;
  return `${label}: ${value ? 'Reached' : 'Not reached'}`;
}

/**
 * Compact "what happened" summary (Phase 11) — a few lines answering, within ~5 seconds: did
 * automation reach the employer, did it fill anything, why did it stop. Every line is either
 * backed by a real stage or explicitly says so; nothing here is inferred beyond what
 * `ExecutionEvidenceService` already proved.
 */
export function ExecutionEvidenceSummary({ applicationId }: { applicationId: string }) {
  const query = useQuery<ExecutionEvidence>({
    queryKey: ['execution-evidence', applicationId],
    queryFn: () => executionEvidence.get(applicationId),
    staleTime: 30_000,
  });

  if (query.isLoading) {
    return <div className="h-16 animate-pulse rounded-lg border border-dashed border-border bg-muted/20" />;
  }
  const ev = query.data;
  if (!ev || !ev.hasExecution) {
    return (
      <div className="rounded-lg border border-dashed border-border bg-muted/20 p-3 text-xs text-muted-foreground">
        No automation attempt has run for this application yet.
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-border bg-card p-3" data-testid="execution-evidence-summary">
      <p className="text-sm font-semibold text-foreground">
        {statusIcon(ev)} {STATUS_LABEL[ev.state] ?? 'Automation status unknown'}
      </p>
      <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-muted-foreground sm:grid-cols-3">
        <div>{fact('Employer page', ev.employerPageReached)}</div>
        <div>{fact('Form discovered', ev.formDiscovered)}</div>
        <div>Fields filled: {ev.fieldsFilled ?? 'Unknown'}</div>
      </dl>
      {ev.automationStopped && ev.stopReason && (
        <p className="mt-2 text-xs text-warning">Stopped: {ev.stopReason}</p>
      )}
      {!ev.instrumentationEnabled && (
        <p className="mt-2 text-[11px] text-muted-foreground/70">
          Detailed stage instrumentation is off for this deployment — only the coarse status above is available.
        </p>
      )}
    </div>
  );
}
