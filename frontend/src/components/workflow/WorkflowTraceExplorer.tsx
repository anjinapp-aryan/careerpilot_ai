import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  GitBranch,
  Loader2,
  Search,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs } from '@/components/ui/tabs';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { cn } from '@/lib/cn';
import type {
  CorrelationDiagnostics,
  WorkflowEventTrace,
  WorkflowGraph,
  WorkflowSummary,
  WorkflowTrace,
} from '@/types/workflow';

const STATUS_TONE: Record<string, BadgeTone> = {
  SUCCESS: 'success',
  COMPLETED: 'success',
  RUNNING: 'info',
  PENDING: 'neutral',
  NOT_STARTED: 'neutral',
  SKIPPED: 'neutral',
  FAILED: 'danger',
  PARTIAL: 'warning',
};

function statusTone(status?: string | null): BadgeTone {
  return STATUS_TONE[(status ?? '').toUpperCase()] ?? 'neutral';
}

function fmt(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function fmtMs(ms?: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

type ExplorerTab = 'summary' | 'timeline' | 'graph' | 'events' | 'diagnostics' | 'deadletters';

const TABS: { value: ExplorerTab; label: string }[] = [
  { value: 'summary', label: 'Summary' },
  { value: 'timeline', label: 'Timeline' },
  { value: 'graph', label: 'Graph' },
  { value: 'events', label: 'Events' },
  { value: 'diagnostics', label: 'Diagnostics' },
  { value: 'deadletters', label: 'Dead Letters' },
];

interface WorkflowTraceExplorerProps {
  /** Pre-filled correlation id (e.g. deep-linked from a "seed" response). */
  initialCorrelationId?: string | null;
  /** Compact = no outer Card chrome (for embedding inside another dialog/drawer). */
  compact?: boolean;
}

/**
 * Phase 4D — Workflow Trace Explorer. Reads the read-only Phase 3A follow-up Correlation Trace
 * API (WorkflowTraceController). Every workflow engine ships dark, so a correlation id that
 * doesn't exist (or was never tracked) 404s — rendered as "not found", never a crash. Shared
 * between the Workflow page, the Application drawer, and Admin's Correlation Explorer.
 */
export function WorkflowTraceExplorer({ initialCorrelationId, compact }: WorkflowTraceExplorerProps) {
  const [input, setInput] = useState(initialCorrelationId ?? '');
  const [correlationId, setCorrelationId] = useState<string | null>(initialCorrelationId ?? null);
  const [tab, setTab] = useState<ExplorerTab>('summary');

  const summary = useQuery<WorkflowSummary | null>({
    queryKey: ['workflow-trace', 'summary', correlationId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/workflow/correlation/${correlationId}/summary`)).data;
      } catch {
        return null;
      }
    },
    enabled: !!correlationId,
    retry: false,
  });

  const trace = useQuery<WorkflowTrace | null>({
    queryKey: ['workflow-trace', 'full', correlationId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/workflow/correlation/${correlationId}`)).data;
      } catch {
        return null;
      }
    },
    enabled: !!correlationId && (tab === 'timeline' || tab === 'summary'),
    retry: false,
  });

  const graph = useQuery<WorkflowGraph | null>({
    queryKey: ['workflow-trace', 'graph', correlationId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/workflow/correlation/${correlationId}/graph`)).data;
      } catch {
        return null;
      }
    },
    enabled: !!correlationId && tab === 'graph',
    retry: false,
  });

  const events = useQuery<WorkflowEventTrace[]>({
    queryKey: ['workflow-trace', 'events', correlationId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/workflow/correlation/${correlationId}/events`)).data;
      } catch {
        return [];
      }
    },
    enabled: !!correlationId && tab === 'events',
    retry: false,
  });

  const diagnostics = useQuery<CorrelationDiagnostics | null>({
    queryKey: ['workflow-trace', 'diagnostics', correlationId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/workflow/diagnostics/correlation/${correlationId}`)).data;
      } catch {
        return null;
      }
    },
    enabled: !!correlationId && tab === 'diagnostics',
    retry: false,
  });

  const deadLetterLedger = useQuery<Record<string, unknown> | null>({
    queryKey: ['workflow-trace', 'dead-letter-ledger'],
    queryFn: async () => {
      try {
        return (await api.get('/api/diagnostics/workflow-dead-letter')).data;
      } catch {
        return null;
      }
    },
    enabled: tab === 'deadletters',
    retry: false,
    staleTime: 30_000,
  });

  function lookup() {
    setCorrelationId(input.trim() || null);
  }

  const body = (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && lookup()}
            placeholder="Paste a workflow correlation id…"
            className="pl-9"
          />
        </div>
        <Button size="sm" onClick={lookup} disabled={!input.trim()}>
          Look up
        </Button>
      </div>

      {!correlationId ? (
        <EmptyState
          icon={GitBranch}
          title="No correlation selected"
          description="Paste a correlation id to inspect a Phase 3A workflow's trace, graph, raw events, and diagnostics."
        />
      ) : (
        <>
          <Tabs items={TABS} value={tab} onChange={(v) => setTab(v as ExplorerTab)} />

          {tab === 'summary' && <SummaryTab summary={summary.data} loading={summary.isLoading} />}
          {tab === 'timeline' && <TimelineTab trace={trace.data} loading={trace.isLoading} />}
          {tab === 'graph' && <GraphTab graph={graph.data} loading={graph.isLoading} />}
          {tab === 'events' && <EventsTab events={events.data ?? []} loading={events.isLoading} />}
          {tab === 'diagnostics' && (
            <DiagnosticsTab diagnostics={diagnostics.data} loading={diagnostics.isLoading} />
          )}
          {tab === 'deadletters' && (
            <DeadLettersTab
              ledger={deadLetterLedger.data}
              loading={deadLetterLedger.isLoading}
              traceDeadLetters={trace.data?.deadLetters ?? []}
            />
          )}
        </>
      )}
    </div>
  );

  if (compact) return body;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <GitBranch className="h-4 w-4 text-muted-foreground" /> Correlation Explorer
        </CardTitle>
        <p className="text-sm text-muted-foreground">
          Phase 3A workflow trace — timeline, graph, raw events, and dead letters for one correlation id.
        </p>
      </CardHeader>
      <CardContent>{body}</CardContent>
    </Card>
  );
}

function SummaryTab({ summary, loading }: { summary: WorkflowSummary | null | undefined; loading: boolean }) {
  if (loading) return <Skeleton className="h-24 w-full" />;
  if (!summary) return <p className="text-sm text-muted-foreground">No trace found for this correlation id.</p>;
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2.5">
        <span className="text-sm font-medium text-foreground">{summary.workflowType}</span>
        <Badge tone={statusTone(summary.status)}>{summary.status}</Badge>
      </div>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Stat label="Steps" value={summary.totalSteps} />
        <Stat label="Completed" value={summary.completedSteps} />
        <Stat label="Failed" value={summary.failedSteps} tone={summary.failedSteps > 0 ? 'danger' : undefined} />
        <Stat label="Dead letters" value={summary.deadLetterCount} tone={summary.deadLetterCount > 0 ? 'warning' : undefined} />
      </div>
      <p className="text-xs text-muted-foreground">Duration: {fmtMs(summary.durationMs)}</p>
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'danger' | 'warning' }) {
  return (
    <div className="rounded-lg border border-border px-3 py-2 text-center">
      <p className={cn('text-lg font-semibold tabular-nums', tone === 'danger' && 'text-danger', tone === 'warning' && 'text-warning')}>
        {value}
      </p>
      <p className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</p>
    </div>
  );
}

function stepIcon(status: string) {
  const s = status.toUpperCase();
  if (s === 'SUCCESS' || s === 'COMPLETED') return <CheckCircle2 className="h-4 w-4 text-success" />;
  if (s === 'FAILED') return <XCircle className="h-4 w-4 text-danger" />;
  if (s === 'RUNNING') return <Loader2 className="h-4 w-4 animate-spin text-secondary" />;
  return <Clock className="h-4 w-4 text-muted-foreground" />;
}

function TimelineTab({ trace, loading }: { trace: WorkflowTrace | null | undefined; loading: boolean }) {
  if (loading) return <Skeleton className="h-40 w-full" />;
  if (!trace) return <p className="text-sm text-muted-foreground">No trace found for this correlation id.</p>;
  return (
    <ol className="space-y-3 border-l border-border pl-4">
      {trace.steps.map((s, i) => (
        <li key={`${s.step}-${i}`} className="relative">
          <span className="absolute -left-[21px] top-0.5">{stepIcon(s.status)}</span>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium text-foreground">{s.step}</span>
            <Badge tone={statusTone(s.status)}>{s.status}</Badge>
            {s.durationMs != null && <span className="text-xs text-muted-foreground">{fmtMs(s.durationMs)}</span>}
          </div>
          {s.startedAt && (
            <p className="text-[11px] text-muted-foreground">
              {fmt(s.startedAt)}{s.completedAt ? ` → ${fmt(s.completedAt)}` : ''}
            </p>
          )}
        </li>
      ))}
      {trace.steps.length === 0 && <p className="text-sm text-muted-foreground">No steps recorded yet.</p>}
    </ol>
  );
}

function GraphTab({ graph, loading }: { graph: WorkflowGraph | null | undefined; loading: boolean }) {
  if (loading) return <Skeleton className="h-32 w-full" />;
  if (!graph || graph.nodes.length === 0) {
    return <p className="text-sm text-muted-foreground">No graph available for this correlation id.</p>;
  }
  return (
    <div className="flex flex-wrap items-center gap-1 overflow-x-auto pb-1">
      {graph.nodes.map((n, i) => (
        <div key={n.id} className="flex items-center gap-1">
          <div
            className={cn(
              'flex flex-col items-center rounded-lg border px-3 py-2 text-center',
              statusTone(n.status) === 'success' && 'border-success/40 bg-success/5',
              statusTone(n.status) === 'danger' && 'border-danger/40 bg-danger/5',
              statusTone(n.status) === 'info' && 'border-secondary/40 bg-secondary/5',
              statusTone(n.status) === 'neutral' && 'border-border bg-muted/30',
            )}
          >
            <span className="whitespace-nowrap text-xs font-medium text-foreground">{n.label}</span>
            <Badge tone={statusTone(n.status)} className="mt-1 text-[10px]">{n.status}</Badge>
          </div>
          {i < graph.nodes.length - 1 && <span className="h-px w-5 shrink-0 bg-border" />}
        </div>
      ))}
    </div>
  );
}

function EventsTab({ events, loading }: { events: WorkflowEventTrace[]; loading: boolean }) {
  if (loading) return <Skeleton className="h-40 w-full" />;
  if (events.length === 0) return <p className="text-sm text-muted-foreground">No events recorded for this correlation id.</p>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
            <th className="py-2 pr-4">Event</th>
            <th className="py-2 pr-4">Status</th>
            <th className="py-2 pr-4">Timestamp</th>
          </tr>
        </thead>
        <tbody>
          {events.map((e) => (
            <tr key={e.eventId} className="border-b border-border/50">
              <td className="py-2 pr-4 font-medium">{e.eventType}</td>
              <td className="py-2 pr-4"><Badge tone={statusTone(e.status)}>{e.status}</Badge></td>
              <td className="py-2 pr-4 text-muted-foreground">{fmt(e.timestamp)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DiagnosticsTab({ diagnostics, loading }: { diagnostics: CorrelationDiagnostics | null | undefined; loading: boolean }) {
  if (loading) return <Skeleton className="h-24 w-full" />;
  if (!diagnostics) return <p className="text-sm text-muted-foreground">No diagnostics available for this correlation id.</p>;
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
      <Stat label="Events" value={diagnostics.totalEvents} />
      <Stat label="Completed stages" value={diagnostics.completedStages} />
      <Stat label="Failed stages" value={diagnostics.failedStages} tone={diagnostics.failedStages > 0 ? 'danger' : undefined} />
      <Stat label="Dead letters" value={diagnostics.deadLetters} tone={diagnostics.deadLetters > 0 ? 'warning' : undefined} />
    </div>
  );
}

function DeadLettersTab({
  ledger,
  loading,
  traceDeadLetters,
}: {
  ledger: Record<string, unknown> | null | undefined;
  loading: boolean;
  traceDeadLetters: WorkflowTrace['deadLetters'];
}) {
  if (loading) return <Skeleton className="h-24 w-full" />;
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2.5">
        <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
          <AlertTriangle className="h-4 w-4" /> Engine-wide dead-letter ledger
        </span>
        <span className="text-sm font-semibold tabular-nums text-foreground">
          {ledger ? String(ledger.deadLetterRowsTotal ?? 0) : '—'}
        </span>
      </div>
      {traceDeadLetters.length === 0 ? (
        <p className="text-sm text-muted-foreground">No dead-lettered stages for this correlation id.</p>
      ) : (
        <div className="space-y-2">
          {traceDeadLetters.map((dl, i) => (
            <div key={i} className="rounded-lg border border-danger/30 bg-danger/5 p-3 text-sm">
              <div className="flex items-center justify-between">
                <span className="font-medium text-foreground">{dl.worker} · {dl.stage}</span>
                <span className="text-xs text-muted-foreground">{fmt(dl.timestamp)}</span>
              </div>
              <p className="mt-1 text-xs text-danger">{dl.error}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
