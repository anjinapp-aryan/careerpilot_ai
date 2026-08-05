import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  CheckCircle2,
  Clock,
  Gauge,
  Pause,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  Timer,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Tabs } from '@/components/ui/tabs';
import { Card } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { EmptyState } from '@/components/ui/empty-state';
import { Skeleton } from '@/components/ui/skeleton';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { BrowserValidationPanel } from '@/components/execution/BrowserValidationPanel';

const TABS = [
  { value: 'dashboard', label: 'Dashboard' },
  { value: 'fleet', label: 'Fleet View' },
  { value: 'queues', label: 'Queue Monitor' },
  // Phase 12C.5 — the browser validation harness. Placed here rather than on a route of its own:
  // this page is already admin-only and already the home for automation observability.
  { value: 'validation', label: 'Browser Validation' },
];

interface OperationsSummary {
  enabled: boolean;
  running?: number; queued?: number; waitingApproval?: number; retrying?: number;
  recovered?: number; paused?: number; cancelled?: number; verificationPending?: number;
  verificationFailed?: number; completed?: number; failed?: number; aborted?: number;
  avgSubmissionTimeMs?: number; avgVerificationTimeMs?: number; avgRecoveryTimeMs?: number;
  automationSuccessRate?: number; recoverySuccessRate?: number; verificationSuccessRate?: number;
}

interface FleetProvider {
  provider: string; configured: boolean; guestApplyEligible: boolean;
  runningJobs: number; failures: number; recoveryCount: number;
  avgSubmissionTimeMs?: number | null; successRate?: number | null;
  lastFailure?: string | null; lastSuccess?: string | null; currentStatus: string;
}

interface OperationsFleet { enabled: boolean; providers?: FleetProvider[]; windowSize?: number }

interface QueueInfo { items: number; oldestItem?: string | null; newestItem?: string | null; averageWaitMs?: number | null; processingRate?: number | null }
interface OperationsQueues {
  enabled: boolean;
  executionQueue?: QueueInfo; retryQueue?: QueueInfo; recoveryQueue?: QueueInfo; manualQueue?: QueueInfo;
  waitingApprovalQueue?: QueueInfo; runningQueue?: QueueInfo; completedQueue?: QueueInfo; cancelledQueue?: QueueInfo;
}

function fmtMs(ms?: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function fmtPct(n?: number | null): string {
  return n == null ? '—' : `${n.toFixed(1)}%`;
}

function fmtDate(iso?: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

const STATUS_TONE: Record<string, BadgeTone> = {
  HEALTHY: 'success', DEGRADED: 'warning', NOT_CONFIGURED: 'neutral', IDLE: 'neutral',
};

/**
 * Phase 7.16.4 — the Application Operations Center: a read-only aggregation view over the
 * execution/retry/verification/recovery pipeline (7.16.1-7.16.3). Reuses the same TanStack Query +
 * try/catch-empty pattern as AdminDashboard.tsx so a disabled `application.operations.enabled` flag
 * degrades to an EmptyState instead of an error.
 */
export default function OperationsCenter() {
  const [tab, setTab] = useState('dashboard');

  const summary = useQuery<OperationsSummary>({
    queryKey: ['operations', 'summary'],
    queryFn: async () => (await api.get('/api/diagnostics/operations/summary')).data,
    retry: false,
    refetchInterval: 30_000,
  });
  const fleet = useQuery<OperationsFleet>({
    queryKey: ['operations', 'fleet'],
    queryFn: async () => (await api.get('/api/diagnostics/operations/fleet')).data,
    retry: false,
    enabled: tab === 'fleet',
    refetchInterval: 30_000,
  });
  const queues = useQuery<OperationsQueues>({
    queryKey: ['operations', 'queues'],
    queryFn: async () => (await api.get('/api/diagnostics/operations/queues')).data,
    retry: false,
    enabled: tab === 'queues',
    refetchInterval: 30_000,
  });

  return (
    <div className="space-y-4">
      <PageHeader
        title="Operations Center"
        description="Real-time visibility over every automated application — running, retrying, recovered, verified."
      />
      <Tabs items={TABS} value={tab} onChange={setTab} />

      {tab === 'dashboard' && <DashboardTab data={summary.data} loading={summary.isLoading} />}
      {tab === 'fleet' && <FleetTab data={fleet.data} loading={fleet.isLoading} />}
      {tab === 'queues' && <QueuesTab data={queues.data} loading={queues.isLoading} />}
      {/* Not gated by application.operations.enabled: the validation harness has its own flag
          (browser.validation.enabled) and its own disabled state, so it stays usable even when
          the operations aggregation view is dark. */}
      {tab === 'validation' && <BrowserValidationPanel />}
    </div>
  );
}

function DisabledNotice() {
  return (
    <EmptyState
      icon={ShieldAlert}
      title="Operations Center is not enabled"
      description="Set application.operations.enabled=true to turn on this read-only aggregation view. Nothing about the underlying automation pipeline changes."
    />
  );
}

function DashboardTab({ data, loading }: { data?: OperationsSummary; loading: boolean }) {
  if (loading) return <SkeletonGrid />;
  if (!data?.enabled) return <DisabledNotice />;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
        <KpiCard label="Running" value={data.running ?? 0} icon={Activity} tone="primary" />
        <KpiCard label="Queued" value={data.queued ?? 0} icon={Clock} tone="info" />
        <KpiCard label="Waiting approval" value={data.waitingApproval ?? 0} icon={Pause} tone="warning" />
        <KpiCard label="Retrying" value={data.retrying ?? 0} icon={RotateCcw} tone="warning" />
        <KpiCard label="Recovered" value={data.recovered ?? 0} icon={RefreshCw} tone="success" />
        <KpiCard label="Manual review" value={data.paused ?? 0} icon={Pause} tone="danger" />
        <KpiCard label="Cancelled" value={data.cancelled ?? 0} icon={XCircle} tone="info" />
        <KpiCard label="Verification failed" value={data.verificationFailed ?? 0} icon={ShieldAlert} tone="danger" />
        <KpiCard label="Completed" value={data.completed ?? 0} icon={CheckCircle2} tone="success" />
        <KpiCard label="Failed" value={data.failed ?? 0} icon={XCircle} tone="danger" />
      </div>

      <Card className="p-5">
        <h3 className="mb-3 text-sm font-semibold text-foreground">Timing & success rates</h3>
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
          <Metric icon={Timer} label="Avg submission time" value={fmtMs(data.avgSubmissionTimeMs)} />
          <Metric icon={Timer} label="Avg verification time" value={fmtMs(data.avgVerificationTimeMs)} />
          <Metric icon={Timer} label="Avg recovery time" value={fmtMs(data.avgRecoveryTimeMs)} />
          <Metric icon={Gauge} label="Automation success" value={fmtPct(data.automationSuccessRate)} />
          <Metric icon={Gauge} label="Recovery success" value={fmtPct(data.recoverySuccessRate)} />
          <Metric icon={Gauge} label="Verification success" value={fmtPct(data.verificationSuccessRate)} />
        </div>
      </Card>
    </div>
  );
}

function Metric({ icon: Icon, label, value }: { icon: typeof Timer; label: string; value: string }) {
  return (
    <div className="flex items-center gap-2.5">
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
        <Icon className="h-4 w-4" />
      </span>
      <div>
        <p className="text-sm font-semibold tabular-nums text-foreground">{value}</p>
        <p className="text-xs text-muted-foreground">{label}</p>
      </div>
    </div>
  );
}

function FleetTab({ data, loading }: { data?: OperationsFleet; loading: boolean }) {
  if (loading) return <SkeletonGrid />;
  if (!data?.enabled) return <DisabledNotice />;
  const providers = data.providers ?? [];
  if (providers.length === 0) {
    return <EmptyState title="No ATS providers registered" description="No connectors are currently registered on this build." />;
  }

  return (
    <Card className="overflow-x-auto p-0">
      <table className="w-full text-sm">
        <thead className="border-b border-border bg-muted/30 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
          <tr>
            <th className="px-4 py-2.5">Provider</th>
            <th className="px-4 py-2.5">Status</th>
            <th className="px-4 py-2.5">Running</th>
            <th className="px-4 py-2.5">Failures</th>
            <th className="px-4 py-2.5">Recoveries</th>
            <th className="px-4 py-2.5">Avg submission</th>
            <th className="px-4 py-2.5">Success rate</th>
            <th className="px-4 py-2.5">Last failure</th>
            <th className="px-4 py-2.5">Last success</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {providers.map((p) => (
            <tr key={p.provider}>
              <td className="px-4 py-2.5 font-medium text-foreground capitalize">{p.provider}</td>
              <td className="px-4 py-2.5"><Badge tone={STATUS_TONE[p.currentStatus] ?? 'neutral'}>{p.currentStatus}</Badge></td>
              <td className="px-4 py-2.5 tabular-nums">{p.runningJobs}</td>
              <td className="px-4 py-2.5 tabular-nums">{p.failures}</td>
              <td className="px-4 py-2.5 tabular-nums">{p.recoveryCount}</td>
              <td className="px-4 py-2.5 tabular-nums">{fmtMs(p.avgSubmissionTimeMs)}</td>
              <td className="px-4 py-2.5 tabular-nums">{fmtPct(p.successRate)}</td>
              <td className="px-4 py-2.5 text-xs text-muted-foreground">{fmtDate(p.lastFailure)}</td>
              <td className="px-4 py-2.5 text-xs text-muted-foreground">{fmtDate(p.lastSuccess)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {data.windowSize != null && (
        <p className="border-t border-border px-4 py-2 text-xs text-muted-foreground">
          Computed over the most recent {data.windowSize} provider-attributed executions.
        </p>
      )}
    </Card>
  );
}

const QUEUE_LABELS: Array<{ key: keyof OperationsQueues; label: string }> = [
  { key: 'executionQueue', label: 'Execution Queue' },
  { key: 'retryQueue', label: 'Retry Queue' },
  { key: 'recoveryQueue', label: 'Recovery Queue' },
  { key: 'manualQueue', label: 'Manual Queue' },
  { key: 'waitingApprovalQueue', label: 'Waiting Approval Queue' },
  { key: 'runningQueue', label: 'Running Queue' },
  { key: 'completedQueue', label: 'Completed Queue' },
  { key: 'cancelledQueue', label: 'Cancelled Queue' },
];

function QueuesTab({ data, loading }: { data?: OperationsQueues; loading: boolean }) {
  if (loading) return <SkeletonGrid />;
  if (!data?.enabled) return <DisabledNotice />;

  return (
    <Card className="overflow-x-auto p-0">
      <table className="w-full text-sm">
        <thead className="border-b border-border bg-muted/30 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
          <tr>
            <th className="px-4 py-2.5">Queue</th>
            <th className="px-4 py-2.5">Items</th>
            <th className="px-4 py-2.5">Oldest item</th>
            <th className="px-4 py-2.5">Newest item</th>
            <th className="px-4 py-2.5">Avg wait</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {QUEUE_LABELS.map(({ key, label }) => {
            const q = data[key] as QueueInfo | undefined;
            return (
              <tr key={key}>
                <td className="px-4 py-2.5 font-medium text-foreground">{label}</td>
                <td className="px-4 py-2.5 tabular-nums">{q?.items ?? 0}</td>
                <td className="px-4 py-2.5 text-xs text-muted-foreground">{fmtDate(q?.oldestItem)}</td>
                <td className="px-4 py-2.5 text-xs text-muted-foreground">{fmtDate(q?.newestItem)}</td>
                <td className="px-4 py-2.5 tabular-nums">{fmtMs(q?.averageWaitMs)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="border-t border-border px-4 py-2 text-xs text-muted-foreground">
        Recovery Queue and Retry Queue report the same underlying set of parked executions — recovery
        re-uses the retry queue rather than maintaining a second one. Processing rate isn't shown: no
        time-series is retained today, only current counts.
      </p>
    </Card>
  );
}

function SkeletonGrid() {
  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
      {Array.from({ length: 5 }).map((_, i) => (
        <Skeleton key={i} className="h-28 rounded-xl" />
      ))}
    </div>
  );
}
