import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2, Circle, Loader2, MinusCircle, Search, XCircle } from 'lucide-react';
import { api } from '@/lib/api';
import { Card } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { EmptyState } from '@/components/ui/empty-state';
import { Skeleton } from '@/components/ui/skeleton';

/**
 * P5 — the execution timeline viewer.
 *
 * Replaces the single word "FAILED" with the stage-by-stage story of one run: what completed, how
 * long each part took, which stage was the last one and why. The point of this panel is that an
 * operator should never have to open a log file to answer "where did this stop".
 */

interface StageRow {
  sequence: number;
  stage: string;
  displayName: string;
  status: 'STARTED' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  failureCategory?: string | null;
  reason?: string | null;
  detail?: string | null;
}

interface ExitSummary {
  stoppedAt?: string | null;
  stoppedAtDisplayName?: string | null;
  outcome?: string | null;
  reason?: string | null;
  failureCategory?: string | null;
  recoveryAction?: string | null;
  retryOfExecutionId?: string | null;
}

interface BrowserSnapshot {
  launched?: boolean;
  launchedAt?: string | null;
  uptimeSeconds?: number | null;
  contextsServed?: number | null;
  openContexts?: number | null;
  activeLeases?: number | null;
  maxLeases?: number | null;
  note?: string | null;
}

interface TimelineResponse {
  executionId: string;
  jobId?: string | null;
  executionStatus?: string | null;
  checkpoint?: string | null;
  instrumentationEnabled: boolean;
  stages: StageRow[];
  stageDurations: Record<string, number>;
  totalDurationMs?: number | null;
  exit?: ExitSummary | null;
  answers?: { stage: string; detail: string } | null;
  browser?: BrowserSnapshot | null;
  note?: string | null;
}

function fmtMs(ms?: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

function fmtTime(iso?: string | null): string {
  return iso ? new Date(iso).toLocaleTimeString() : '—';
}

function statusIcon(status: StageRow['status']) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle2 className="h-4 w-4 text-[var(--success)]" aria-hidden />;
    case 'FAILED':
      return <XCircle className="h-4 w-4 text-[var(--danger)]" aria-hidden />;
    case 'STARTED':
      return <Loader2 className="h-4 w-4 animate-spin text-[var(--muted-foreground)]" aria-hidden />;
    default:
      return <MinusCircle className="h-4 w-4 text-[var(--muted-foreground)]" aria-hidden />;
  }
}

function outcomeTone(outcome?: string | null): BadgeTone {
  switch (outcome) {
    case 'COMPLETED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'IN_FLIGHT':
      return 'info';
    default:
      return 'warning';
  }
}

export function ExecutionTimelinePanel() {
  const [input, setInput] = useState('');
  const [executionId, setExecutionId] = useState<string | null>(null);

  const timeline = useQuery<TimelineResponse>({
    queryKey: ['execution-timeline', executionId],
    queryFn: async () =>
      (await api.get(`/api/execution/executions/${executionId}/timeline`)).data,
    enabled: !!executionId,
    retry: false,
  });

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            setExecutionId(input.trim() || null);
          }}
        >
          <label className="flex-1 min-w-[18rem] text-sm">
            <span className="mb-1 block text-[var(--muted-foreground)]">Execution ID</span>
            <input
              className="w-full rounded-md border border-[var(--border)] bg-[var(--background)] px-3 py-2 text-sm"
              placeholder="00000000-0000-0000-0000-000000000000"
              value={input}
              onChange={(e) => setInput(e.target.value)}
            />
          </label>
          <button
            type="submit"
            className="inline-flex items-center gap-2 rounded-md bg-[var(--primary)] px-4 py-2 text-sm text-[var(--primary-foreground)] disabled:opacity-50"
            disabled={!input.trim()}
          >
            <Search className="h-4 w-4" aria-hidden />
            Load timeline
          </button>
        </form>
      </Card>

      {!executionId && (
        <EmptyState
          title="No execution selected"
          description="Enter an execution ID to see exactly where that application stopped and how long every stage took."
        />
      )}

      {executionId && timeline.isLoading && <Skeleton className="h-64 w-full" />}

      {executionId && timeline.isError && (
        <EmptyState
          title="Timeline unavailable"
          description="That execution does not exist, is not yours, or the operations surface is disabled."
        />
      )}

      {timeline.data && <TimelineBody data={timeline.data} />}
    </div>
  );
}

function TimelineBody({ data }: { data: TimelineResponse }) {
  const exit = data.exit;

  return (
    <div className="space-y-4">
      {/* The headline: where it stopped and why, before any detail. */}
      <Card className="p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="text-sm text-[var(--muted-foreground)]">Stopped at</div>
            <div className="text-lg font-medium">
              {exit?.stoppedAtDisplayName ?? exit?.stoppedAt ?? 'No stages recorded'}
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={outcomeTone(exit?.outcome)}>{exit?.outcome ?? 'UNKNOWN'}</Badge>
            {exit?.failureCategory && <Badge tone="danger">{exit.failureCategory}</Badge>}
            {exit?.recoveryAction && <Badge tone="info">Recovery: {exit.recoveryAction}</Badge>}
          </div>
        </div>
        {exit?.reason && (
          <p className="mt-3 rounded-md bg-[var(--muted)] p-3 text-sm">{exit.reason}</p>
        )}
        <div className="mt-3 flex flex-wrap gap-4 text-sm text-[var(--muted-foreground)]">
          <span>Total: {fmtMs(data.totalDurationMs)}</span>
          <span>Status: {data.executionStatus ?? '—'}</span>
          {data.checkpoint && <span>Checkpoint: {data.checkpoint}</span>}
        </div>
      </Card>

      {/* "Instrumentation is off" and "this run recorded nothing" are different facts. */}
      {data.note && (
        <Card className="flex items-start gap-3 p-4">
          <AlertTriangle className="mt-0.5 h-4 w-4 text-[var(--warning)]" aria-hidden />
          <p className="text-sm">{data.note}</p>
        </Card>
      )}

      {data.stages.length > 0 && (
        <Card className="p-0">
          <ol className="divide-y divide-[var(--border)]">
            {data.stages.map((s) => (
              <li key={`${s.sequence}-${s.stage}`} className="flex items-start gap-3 p-3">
                <span className="mt-0.5">{statusIcon(s.status)}</span>
                <span className="w-20 shrink-0 text-xs text-[var(--muted-foreground)]">
                  {fmtTime(s.startedAt)}
                </span>
                <span className="flex-1 min-w-0">
                  <span className="block text-sm font-medium">{s.displayName}</span>
                  {s.reason && (
                    <span className="mt-1 block break-words text-xs text-[var(--muted-foreground)]">
                      {s.reason}
                    </span>
                  )}
                  {s.detail && (
                    <span className="mt-1 block break-words font-mono text-[11px] text-[var(--muted-foreground)]">
                      {s.detail}
                    </span>
                  )}
                </span>
                <span className="w-20 shrink-0 text-right text-xs tabular-nums">
                  {fmtMs(s.durationMs)}
                </span>
                {s.failureCategory && <Badge tone="danger">{s.failureCategory}</Badge>}
              </li>
            ))}
          </ol>
        </Card>
      )}

      {data.browser && (
        <Card className="p-4">
          <h3 className="mb-2 text-sm font-medium">Browser</h3>
          <div className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
            <Stat label="Launched" value={data.browser.launched ? 'yes' : 'no'} />
            <Stat label="Uptime" value={data.browser.uptimeSeconds != null ? `${data.browser.uptimeSeconds}s` : '—'} />
            <Stat label="Contexts served" value={String(data.browser.contextsServed ?? '—')} />
            <Stat
              label="Leases"
              value={`${data.browser.activeLeases ?? '—'} / ${data.browser.maxLeases ?? '—'}`}
            />
          </div>
          {/* The browser may have been recycled since; saying so beats implying otherwise. */}
          {data.browser.note && (
            <p className="mt-2 text-xs text-[var(--muted-foreground)]">{data.browser.note}</p>
          )}
        </Card>
      )}

      {data.answers && (
        <Card className="p-4">
          <h3 className="mb-2 text-sm font-medium">Answer resolution</h3>
          <p className="break-words font-mono text-xs text-[var(--muted-foreground)]">
            {data.answers.detail}
          </p>
        </Card>
      )}

      {data.stages.length === 0 && !data.note && (
        <EmptyState title="No stages" description="This execution recorded no stage events." />
      )}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-[var(--muted-foreground)]">{label}</div>
      <div className="tabular-nums">{value}</div>
    </div>
  );
}

export { Circle };
