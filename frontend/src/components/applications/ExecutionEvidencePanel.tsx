import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { executionEvidence } from '@/lib/executionEvidence';
import { ExecutionEvidenceSummary } from './ExecutionEvidenceSummary';
import type { ExecutionEvidence, ExecutionStageEntry } from '@/types/workflow';

const SAFE_STOP_CATEGORIES = new Set(['ATS_DETECTION', 'QUESTION_RESOLUTION']);

function stageIcon(row: ExecutionStageEntry): string {
  if (row.status === 'COMPLETED') return '✓';
  if (row.status === 'SKIPPED') return '·';
  if (row.status === 'FAILED') {
    return row.failureCategory && SAFE_STOP_CATEGORIES.has(row.failureCategory) ? '⚠' : '⛔';
  }
  return '…'; // STARTED and never closed — still in flight, or the run was killed mid-stage.
}

/**
 * P7 Action 7 — Execution Visibility, Phase 8: the compact stage-by-stage timeline. Reuses
 * `ExecutionEvidence.timeline` verbatim (same rows `ExecutionTimelineService` already renders) —
 * no separate fetch, no re-derivation. Raw stage/failureCategory/detail JSON is pushed into a
 * collapsible "Technical details" section so the default view stays readable.
 */
export function ExecutionEvidencePanel({ applicationId }: { applicationId: string }) {
  const [showTechnical, setShowTechnical] = useState(false);
  const query = useQuery<ExecutionEvidence>({
    queryKey: ['execution-evidence', applicationId],
    queryFn: () => executionEvidence.get(applicationId),
    staleTime: 30_000,
  });

  if (query.isLoading) return <div className="h-40 animate-pulse rounded-lg border border-dashed border-border bg-muted/20" />;
  const ev = query.data;
  if (!ev || !ev.hasExecution) {
    return (
      <p className="rounded-lg border border-dashed border-border bg-muted/20 p-4 text-sm text-muted-foreground">
        No automation execution has run for this application yet.
      </p>
    );
  }

  return (
    <div className="space-y-4" data-testid="execution-evidence-panel">
      <ExecutionEvidenceSummary applicationId={applicationId} />

      {ev.timeline.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border bg-muted/20 p-3 text-xs text-muted-foreground">
          {ev.instrumentationEnabled === false
            ? 'Stage-by-stage instrumentation is disabled for this deployment, so no detailed timeline is available.'
            : 'No stage events recorded for this run.'}
        </p>
      ) : (
        <div>
          <h4 className="mb-2 text-sm font-semibold text-foreground">Execution timeline</h4>
          <ul className="space-y-1.5">
            {ev.timeline.map((row) => (
              <li key={row.sequence} className="flex items-start gap-2 text-xs">
                <span className="mt-0.5 w-4 shrink-0 text-center">{stageIcon(row)}</span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-baseline gap-x-2">
                    <span className="font-medium text-foreground">{row.displayName}</span>
                    {row.startedAt && (
                      <span className="text-[11px] text-muted-foreground">{new Date(row.startedAt).toLocaleTimeString()}</span>
                    )}
                  </div>
                  {row.reason && <p className="text-muted-foreground">{row.reason}</p>}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div>
        <button
          type="button"
          onClick={() => setShowTechnical((v) => !v)}
          className="text-xs font-medium text-primary hover:underline"
        >
          {showTechnical ? 'Hide' : 'Show'} technical details
        </button>
        {showTechnical && (
          <pre className="mt-2 max-h-64 overflow-auto rounded-lg border border-border bg-muted/30 p-3 text-[11px] text-muted-foreground">
            {JSON.stringify(ev, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
}
