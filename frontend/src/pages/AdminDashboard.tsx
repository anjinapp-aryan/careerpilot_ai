import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip as RTooltip, XAxis, YAxis } from 'recharts';
import { Activity, Copy, Database, Globe2, RefreshCw, Server, ShieldCheck, Sparkles, Trash2, Wand2 } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { useToast } from '@/components/ui/toast';
import { WorkflowTraceExplorer } from '@/components/workflow/WorkflowTraceExplorer';
import { DailyDiscoveryHealthPanel } from '@/components/admin/DailyDiscoveryHealthPanel';
import { JobProviderHealthPanel } from '@/components/admin/JobProviderHealthPanel';
import { AutopilotHealthPanel } from '@/components/admin/AutopilotHealthPanel';
import { PackageIntelligencePanel } from '@/components/admin/PackageIntelligencePanel';
import { ReviewPipelinePanel } from '@/components/admin/ReviewPipelinePanel';
import { CompanyIntelligencePanel } from '@/components/admin/CompanyIntelligencePanel';
import { StoryIntelligencePanel } from '@/components/admin/StoryIntelligencePanel';

/** Bar-chart rows for an "Unknown" bucket are real data but not actionable — muted instead of the brand color. */
const UNKNOWN_LABEL = 'Unknown';

interface ProviderHealthEntry {
  provider: string;
  lastStatus: string;
  lastRunAt: string | null;
  lastJobsFetched: number;
  lastJobsPersisted: number;
  lastErrorMessage: string | null;
  successCount30d: number;
  totalRuns30d: number;
  successRatePercent: number;
}

interface CountEntry {
  label: string;
  count: number;
}

interface DiscoveryStats {
  totalDiscovered: number;
  totalEmbedded: number;
  byCountry: CountEntry[];
  bySource: CountEntry[];
}

interface SalaryBandEntry {
  seniorityLevel: string;
  currency: string;
  avgMin: number | null;
  avgMax: number | null;
  count: number;
}

interface DuplicateCluster {
  canonicalJobId: string;
  canonicalTitle: string;
  canonicalCompany: string;
  memberCount: number;
}

interface DuplicateStats {
  totalGroups: number;
  totalDuplicateJobs: number;
  topClusters: DuplicateCluster[];
}

const STATUS_TONE: Record<string, 'success' | 'danger' | 'info' | 'neutral'> = {
  SUCCESS: 'success',
  FAILED: 'danger',
  RUNNING: 'info',
};

const HEALTH_TONE: Record<string, 'success' | 'warning' | 'danger' | 'neutral'> = {
  UP: 'success',
  HEALTHY: 'success',
  DEGRADED: 'warning',
  DOWN: 'danger',
  NOT_CONFIGURED: 'neutral',
  UNKNOWN: 'neutral',
};

function healthTone(v?: string): 'success' | 'warning' | 'danger' | 'neutral' {
  return HEALTH_TONE[(v ?? '').toUpperCase()] ?? 'neutral';
}

interface RetentionStatus {
  enabled?: boolean;
  purged?: Record<string, number>;
}

/** One row of the Phase 4G engine-health matrix — a diagnostics endpoint to poll + a display label. */
const HEALTH_ENDPOINTS: { group: string; label: string; path: string }[] = [
  // Phase 3A — workflow engine (WorkflowDiagnosticsController)
  { group: 'Workflow', label: 'Application Tracking', path: '/api/diagnostics/application-tracking' },
  { group: 'Workflow', label: 'Timeline', path: '/api/diagnostics/application-timeline' },
  { group: 'Workflow', label: 'Email Intelligence', path: '/api/diagnostics/email-intelligence' },
  { group: 'Workflow', label: 'Interview Tracking', path: '/api/diagnostics/interview-tracking' },
  { group: 'Workflow', label: 'Application Analytics', path: '/api/diagnostics/application-analytics' },
  { group: 'Workflow', label: 'Career Intelligence', path: '/api/diagnostics/career-intelligence' },
  { group: 'Workflow', label: 'Correlation Ledger', path: '/api/diagnostics/workflow-correlation' },
  { group: 'Workflow', label: 'Dead Letter Ledger', path: '/api/diagnostics/workflow-dead-letter' },
  // Phase 2E — execution engine (ExecutionDiagnosticsController)
  { group: 'Execution', label: 'Application Execution', path: '/api/diagnostics/application-execution' },
  { group: 'Execution', label: 'Browser Automation', path: '/api/diagnostics/browser' },
  { group: 'Execution', label: 'ATS Submission', path: '/api/diagnostics/ats' },
  { group: 'Execution', label: 'Tracking', path: '/api/diagnostics/tracking' },
  { group: 'Execution', label: 'Analytics', path: '/api/diagnostics/analytics' },
  // Phase 2D — resume pipeline (DiagnosticsController + PipelineDiagnosticsController)
  { group: 'Resume Pipeline', label: 'Resume Tailoring', path: '/api/diagnostics/resume-tailoring' },
  { group: 'Resume Pipeline', label: 'ATS Optimization', path: '/api/diagnostics/ats-optimization' },
  { group: 'Resume Pipeline', label: 'Gap Analysis', path: '/api/diagnostics/gap-analysis' },
  { group: 'Resume Pipeline', label: 'ATS Explainability', path: '/api/diagnostics/ats-explainability' },
  { group: 'Resume Pipeline', label: 'Cover Letter', path: '/api/diagnostics/cover-letter' },
  { group: 'Resume Pipeline', label: 'Application Package', path: '/api/diagnostics/application-package' },
  { group: 'Resume Pipeline', label: 'Auto-Apply Package', path: '/api/diagnostics/auto-apply-package' },
];

interface EngineHealthRow {
  label: string;
  group: string;
  enabled?: boolean;
  health: string;
  queue?: string;
}

/**
 * Phase 4G — one no-auth counts-only diagnostics call per engine stage, fetched in parallel and
 * rendered as a single health matrix. Every call is independently dark-tolerant: a failed fetch
 * (engine not registered, or a transient error) reads as NOT_CONFIGURED rather than an error row.
 */
function EngineHealthMatrix() {
  const { data, isLoading } = useQuery<EngineHealthRow[]>({
    queryKey: ['admin', 'engine-health-matrix'],
    queryFn: async () => {
      const results = await Promise.allSettled(HEALTH_ENDPOINTS.map((e) => api.get(e.path)));
      return HEALTH_ENDPOINTS.map((e, i) => {
        const r = results[i];
        if (r.status !== 'fulfilled') return { label: e.label, group: e.group, health: 'NOT_CONFIGURED' };
        const d = r.value.data as Record<string, unknown>;
        const qSize = d.executorQueueSize;
        const qCap = d.executorQueueCapacity;
        return {
          label: e.label,
          group: e.group,
          enabled: Boolean(d.enabled),
          health: String(d.health ?? 'UNKNOWN'),
          queue: typeof qSize === 'number' && typeof qCap === 'number' ? `${qSize} / ${qCap}` : undefined,
        };
      });
    },
    retry: false,
    staleTime: 30_000,
  });

  const groups = ['Workflow', 'Execution', 'Resume Pipeline'];

  return (
    <Card>
      <CardHeader className="flex-row items-center gap-2">
        <Server className="h-4 w-4 text-muted-foreground" />
        <CardTitle>Engine health matrix</CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <div className="space-y-5">
            {groups.map((g) => {
              const rows = (data ?? []).filter((r) => r.group === g);
              if (rows.length === 0) return null;
              return (
                <div key={g}>
                  <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{g}</h4>
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                          <th className="py-2 pr-4">Stage</th>
                          <th className="py-2 pr-4">Flag</th>
                          <th className="py-2 pr-4">Health</th>
                          <th className="py-2 pr-4">Queue</th>
                        </tr>
                      </thead>
                      <tbody>
                        {rows.map((r) => (
                          <tr key={r.label} className="border-b border-border/50">
                            <td className="py-2 pr-4 font-medium">{r.label}</td>
                            <td className="py-2 pr-4">
                              <Badge tone={r.enabled ? 'info' : 'neutral'}>{r.enabled ? 'enabled' : 'off'}</Badge>
                            </td>
                            <td className="py-2 pr-4">
                              <Badge tone={healthTone(r.health)}>{r.health}</Badge>
                            </td>
                            <td className="py-2 pr-4 tabular-nums text-muted-foreground">{r.queue ?? '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

interface ProviderStatusRow {
  name: string;
  displayName?: string;
  configured?: boolean;
  model?: string | null;
  status?: string;
  circuitState?: string;
  health?: string;
}

/**
 * Phase 4.1 — AI provider analytics: the failover chain's per-provider status (GET /api/ai/providers)
 * joined with the gateway's call/failure/latency/fallback counters (GET /api/diagnostics/ai →
 * gateway_stats: {key}Calls/{key}Failures/{key}RateLimits/{key}AvgLatencyMs + fallbackCount).
 */
function ProviderAnalyticsPanel() {
  const providers = useQuery<ProviderStatusRow[]>({
    queryKey: ['admin', 'ai-providers'],
    queryFn: async () => {
      try {
        return (await api.get('/api/ai/providers')).data;
      } catch {
        return [];
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const diag = useQuery<Record<string, any> | null>({
    queryKey: ['admin', 'ai-diagnostics'],
    queryFn: async () => {
      try {
        return (await api.get('/api/diagnostics/ai')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const rows = providers.data ?? [];
  const stats = (diag.data?.gateway_stats ?? {}) as Record<string, number>;
  const fallbackCount = stats.fallbackCount ?? 0;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle className="flex items-center gap-2">
          <Sparkles className="h-4 w-4 text-muted-foreground" /> AI provider analytics
        </CardTitle>
        <Badge tone={fallbackCount > 0 ? 'warning' : 'neutral'}>{fallbackCount} fallbacks</Badge>
      </CardHeader>
      <CardContent>
        {providers.isLoading ? (
          <Skeleton className="h-32 w-full" />
        ) : rows.length === 0 ? (
          <EmptyState title="Unavailable" description="The AI gateway's provider list could not be loaded." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <th className="py-2 pr-4">Provider</th>
                  <th className="py-2 pr-4">Model</th>
                  <th className="py-2 pr-4">Status</th>
                  <th className="py-2 pr-4">Circuit</th>
                  <th className="py-2 pr-4">Calls</th>
                  <th className="py-2 pr-4">Failures</th>
                  <th className="py-2 pr-4">Rate limits</th>
                  <th className="py-2 pr-4">Avg latency</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((p) => (
                  <tr key={p.name} className="border-b border-border/50">
                    <td className="py-2 pr-4 font-medium capitalize">{p.displayName ?? p.name}</td>
                    <td className="py-2 pr-4 text-xs text-muted-foreground">{p.model ?? '—'}</td>
                    <td className="py-2 pr-4"><Badge tone={healthTone(p.status)}>{p.status ?? '—'}</Badge></td>
                    <td className="py-2 pr-4 text-xs text-muted-foreground">{p.circuitState ?? '—'}</td>
                    <td className="py-2 pr-4 tabular-nums">{stats[`${p.name}Calls`] ?? 0}</td>
                    <td className="py-2 pr-4 tabular-nums">{stats[`${p.name}Failures`] ?? 0}</td>
                    <td className="py-2 pr-4 tabular-nums">{stats[`${p.name}RateLimits`] ?? 0}</td>
                    <td className="py-2 pr-4 tabular-nums text-muted-foreground">
                      {stats[`${p.name}AvgLatencyMs`] != null ? `${Math.round(stats[`${p.name}AvgLatencyMs`])}ms` : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/** Phase 4.1 — MatchCache hit/miss diagnostics (GET /api/diagnostics/match-cache). */
function CacheDiagnosticsPanel() {
  const { data, isLoading } = useQuery<Record<string, any> | null>({
    queryKey: ['admin', 'match-cache'],
    queryFn: async () => {
      try {
        return (await api.get('/api/diagnostics/match-cache')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const hits = Number(data?.matchCacheHits ?? 0);
  const misses = Number(data?.matchCacheMisses ?? 0);
  const total = hits + misses;
  const hitRatio = total > 0 ? Math.round((hits / total) * 100) : null;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle className="flex items-center gap-2">
          <Database className="h-4 w-4 text-muted-foreground" /> Match cache
        </CardTitle>
        {data && (
          <Badge tone={data.enabled ? 'success' : 'neutral'}>{data.enabled ? 'enabled' : 'disabled (dark)'}</Badge>
        )}
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-20 w-full" />
        ) : !data ? (
          <EmptyState title="Unavailable" description="Match-cache diagnostics could not be loaded." />
        ) : (
          <div className="grid grid-cols-3 gap-3 text-center">
            <div className="rounded-lg border border-border px-3 py-2">
              <p className="text-lg font-semibold tabular-nums text-foreground">{hits}</p>
              <p className="text-[11px] uppercase tracking-wide text-muted-foreground">Hits</p>
            </div>
            <div className="rounded-lg border border-border px-3 py-2">
              <p className="text-lg font-semibold tabular-nums text-foreground">{misses}</p>
              <p className="text-[11px] uppercase tracking-wide text-muted-foreground">Misses</p>
            </div>
            <div className="rounded-lg border border-border px-3 py-2">
              <p className="text-lg font-semibold tabular-nums text-foreground">{hitRatio != null ? `${hitRatio}%` : '—'}</p>
              <p className="text-[11px] uppercase tracking-wide text-muted-foreground">Hit ratio</p>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default function AdminDashboard() {
  const qc = useQueryClient();
  const { toast } = useToast();

  const invalidateAll = () => {
    qc.invalidateQueries({ queryKey: ['admin'] });
  };

  const runDiscovery = useMutation({
    mutationFn: async () => (await api.post('/api/jobs/discovery/run')).data,
    onSuccess: (data: { providersRun: number; totalFetched: number; totalPersisted: number }) => {
      toast({ variant: 'success', title: 'Discovery run complete', description: `${data.totalPersisted} jobs persisted across ${data.providersRun} providers.` });
      invalidateAll();
    },
    onError: () => toast({ variant: 'error', title: 'Discovery run failed' }),
  });

  const backfillEmbeddings = useMutation({
    mutationFn: async () => (await api.post('/api/jobs/embeddings/backfill', null, { params: { limit: 100 } })).data,
    onSuccess: (data: { embedded: number }) => {
      toast({ variant: 'success', title: `${data.embedded} jobs embedded` });
      invalidateAll();
    },
    onError: () => toast({ variant: 'error', title: 'Embedding backfill failed' }),
  });

  const backfillEnrichment = useMutation({
    mutationFn: async () => (await api.post('/api/jobs/enrich/backfill', null, { params: { limit: 20 } })).data,
    onSuccess: (data: { enriched: number }) => {
      toast({ variant: 'success', title: `${data.enriched} jobs enriched` });
      invalidateAll();
    },
    onError: () => toast({ variant: 'error', title: 'Enrichment backfill failed' }),
  });

  const backfillDedup = useMutation({
    mutationFn: async () => (await api.post('/api/jobs/dedup/backfill', null, { params: { limit: 100 } })).data,
    onSuccess: (data: { checked: number }) => {
      toast({ variant: 'success', title: `${data.checked} jobs duplicate-checked` });
      invalidateAll();
    },
    onError: () => toast({ variant: 'error', title: 'Duplicate-detection backfill failed' }),
  });

  const { data: providers, isLoading: providersLoading } = useQuery<ProviderHealthEntry[]>({
    queryKey: ['admin', 'provider-health'],
    queryFn: async () => (await api.get('/api/admin/stats/provider-health')).data,
  });

  const { data: discovery, isLoading: discoveryLoading } = useQuery<DiscoveryStats>({
    queryKey: ['admin', 'discovery'],
    queryFn: async () => (await api.get('/api/admin/stats/discovery')).data,
  });

  const { data: skills, isLoading: skillsLoading } = useQuery<CountEntry[]>({
    queryKey: ['admin', 'skill-heatmap'],
    queryFn: async () => (await api.get('/api/admin/stats/skill-heatmap', { params: { limit: 15 } })).data,
  });

  const { data: salary, isLoading: salaryLoading } = useQuery<SalaryBandEntry[]>({
    queryKey: ['admin', 'salary-intelligence'],
    queryFn: async () => (await api.get('/api/admin/stats/salary-intelligence')).data,
  });

  const { data: duplicates, isLoading: duplicatesLoading } = useQuery<DuplicateStats>({
    queryKey: ['admin', 'duplicates'],
    queryFn: async () => (await api.get('/api/admin/stats/duplicates')).data,
  });

  const { data: observability, isLoading: observabilityLoading } = useQuery<Record<string, any>>({
    queryKey: ['admin', 'observability'],
    queryFn: async () => (await api.get('/api/diagnostics/observability')).data,
    retry: false,
    staleTime: 30_000,
  });

  const { data: retention } = useQuery<RetentionStatus | null>({
    queryKey: ['admin', 'retention'],
    queryFn: async () => {
      try {
        return (await api.get('/api/admin/retention/status')).data as RetentionStatus;
      } catch {
        return null; // 403 for non-admins or disabled — treated as "unavailable"
      }
    },
    retry: false,
  });

  const runRetention = useMutation({
    mutationFn: async () => (await api.post('/api/admin/retention/run')).data,
    onSuccess: (data: RetentionStatus) => {
      const total = Object.values(data?.purged ?? {}).reduce((s, n) => s + (n ?? 0), 0);
      toast({ variant: 'success', title: 'Retention run complete', description: `${total} ledger rows purged.` });
      qc.invalidateQueries({ queryKey: ['admin', 'retention'] });
    },
    onError: () => toast({ variant: 'error', title: 'Retention run failed', description: 'Enabled only when RETENTION_ENABLED=true.' }),
  });

  const providerChain = (observability?.providers?.providers ?? {}) as Record<string, string>;

  const embeddedPct = discovery && discovery.totalDiscovered > 0
    ? Math.round((discovery.totalEmbedded / discovery.totalDiscovered) * 100)
    : 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Admin Dashboard"
        description="Job discovery health, AI enrichment coverage, and the skill/salary intelligence it produces."
        actions={
          <>
            <Button
              size="sm"
              variant="outline"
              onClick={() => runDiscovery.mutate()}
              loading={runDiscovery.isPending}
            >
              <RefreshCw className="h-3.5 w-3.5" /> Run discovery
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => backfillEmbeddings.mutate()}
              loading={backfillEmbeddings.isPending}
            >
              <Sparkles className="h-3.5 w-3.5" /> Backfill embeddings
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => backfillEnrichment.mutate()}
              loading={backfillEnrichment.isPending}
            >
              <Wand2 className="h-3.5 w-3.5" /> Backfill enrichment
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => backfillDedup.mutate()}
              loading={backfillDedup.isPending}
            >
              <Copy className="h-3.5 w-3.5" /> Backfill dedup
            </Button>
          </>
        }
      />

      {/* Top-line KPIs */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard
          label="Discovered pool"
          value={discoveryLoading ? '—' : discovery?.totalDiscovered ?? 0}
          icon={Database}
          tone="primary"
          hint="Global discovered-job pool"
        />
        <KpiCard
          label="Embedded for search"
          value={discoveryLoading ? '—' : embeddedPct}
          suffix="%"
          icon={Sparkles}
          tone="info"
          hint={discovery ? `${discovery.totalEmbedded} of ${discovery.totalDiscovered}` : undefined}
        />
        <KpiCard
          label="Active providers"
          value={providersLoading ? '—' : providers?.length ?? 0}
          icon={Globe2}
          tone="success"
          hint="Job-source providers"
        />
        <KpiCard
          label="Enriched skills tracked"
          value={skillsLoading ? '—' : skills?.length ?? 0}
          icon={Activity}
          tone="warning"
          hint="Distinct normalized skills"
        />
        <KpiCard
          label="Duplicate clusters found"
          value={duplicatesLoading ? '—' : duplicates?.totalGroups ?? 0}
          icon={Copy}
          tone="danger"
          hint={duplicates ? `${duplicates.totalDuplicateJobs} duplicate postings` : undefined}
        />
      </div>

      {/* Provider health */}
      <Card>
        <CardHeader>
          <CardTitle>Discovery provider health</CardTitle>
        </CardHeader>
        <CardContent>
          {providersLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : !providers || providers.length === 0 ? (
            <EmptyState title="No discovery runs yet" description="Provider health appears after the first discovery run." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th className="py-2 pr-4">Provider</th>
                    <th className="py-2 pr-4">Last status</th>
                    <th className="py-2 pr-4">Last run</th>
                    <th className="py-2 pr-4">Fetched / persisted</th>
                    <th className="py-2 pr-4">30-day success rate</th>
                  </tr>
                </thead>
                <tbody>
                  {providers.map((p) => (
                    <tr key={p.provider} className="border-b border-border/50">
                      <td className="py-2 pr-4 font-medium capitalize">{p.provider}</td>
                      <td className="py-2 pr-4">
                        <Badge tone={STATUS_TONE[p.lastStatus] ?? 'neutral'}>{p.lastStatus}</Badge>
                      </td>
                      <td className="py-2 pr-4 text-muted-foreground">
                        {p.lastRunAt ? new Date(p.lastRunAt).toLocaleString() : '—'}
                      </td>
                      <td className="py-2 pr-4 tabular-nums">
                        {p.lastJobsFetched} / {p.lastJobsPersisted}
                      </td>
                      <td className="py-2 pr-4 tabular-nums">
                        {p.totalRuns30d > 0 ? `${p.successRatePercent}% (${p.successCount30d}/${p.totalRuns30d})` : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Discovery breakdown */}
        <Card>
          <CardHeader>
            <CardTitle>Pool by country</CardTitle>
          </CardHeader>
          <CardContent>
            {discoveryLoading ? (
              <Skeleton className="h-48 w-full" />
            ) : !discovery || discovery.byCountry.length === 0 ? (
              <EmptyState title="No data yet" description="Run a discovery pass to populate the pool." />
            ) : (
              <div style={{ height: Math.max(224, discovery.byCountry.length * 34) }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={discovery.byCountry} layout="vertical" margin={{ left: 24 }}>
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                    <XAxis type="number" allowDecimals={false} />
                    <YAxis type="category" dataKey="label" width={100} interval={0} />
                    <RTooltip />
                    <Bar dataKey="count" radius={[0, 4, 4, 0]}>
                      {discovery.byCountry.map((entry, i) => (
                        <Cell
                          key={i}
                          fill={entry.label === UNKNOWN_LABEL ? 'hsl(var(--muted-foreground) / 0.35)' : 'hsl(var(--primary))'}
                        />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Skill heatmap */}
        <Card>
          <CardHeader>
            <CardTitle>Top skills in demand</CardTitle>
          </CardHeader>
          <CardContent>
            {skillsLoading ? (
              <Skeleton className="h-48 w-full" />
            ) : !skills || skills.length === 0 ? (
              <EmptyState
                title="No enrichment data yet"
                description="Enable jobs.enrich.ai and run the enrichment backfill to populate this."
              />
            ) : (
              <div style={{ height: Math.max(224, skills.length * 34) }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={skills} layout="vertical" margin={{ left: 24 }}>
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                    <XAxis type="number" allowDecimals={false} />
                    <YAxis type="category" dataKey="label" width={120} interval={0} />
                    <RTooltip />
                    <Bar dataKey="count" radius={[0, 4, 4, 0]}>
                      {skills.map((_, i) => (
                        <Cell key={i} fill="hsl(var(--secondary))" />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Salary intelligence */}
      <Card>
        <CardHeader>
          <CardTitle>Salary intelligence by seniority</CardTitle>
        </CardHeader>
        <CardContent>
          {salaryLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : !salary || salary.length === 0 ? (
            <EmptyState
              title="No salary data yet"
              description="Salary bands appear once jobs are LLM-enriched."
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th className="py-2 pr-4">Seniority</th>
                    <th className="py-2 pr-4">Currency</th>
                    <th className="py-2 pr-4">Avg min</th>
                    <th className="py-2 pr-4">Avg max</th>
                    <th className="py-2 pr-4">Jobs</th>
                  </tr>
                </thead>
                <tbody>
                  {salary.map((s, i) => (
                    <tr key={`${s.seniorityLevel}-${s.currency}-${i}`} className="border-b border-border/50">
                      <td className="py-2 pr-4 font-medium">{s.seniorityLevel}</td>
                      <td className="py-2 pr-4 text-muted-foreground">{s.currency}</td>
                      <td className="py-2 pr-4 tabular-nums">{s.avgMin ? Math.round(s.avgMin).toLocaleString() : '—'}</td>
                      <td className="py-2 pr-4 tabular-nums">{s.avgMax ? Math.round(s.avgMax).toLocaleString() : '—'}</td>
                      <td className="py-2 pr-4 tabular-nums">{s.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Phase 3B.6 — Platform observability rollup */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle className="flex items-center gap-2">
              <Server className="h-4 w-4 text-muted-foreground" /> Platform observability
            </CardTitle>
            {observability?.overall && (
              <Badge tone={healthTone(observability.overall)}>{observability.overall}</Badge>
            )}
          </CardHeader>
          <CardContent>
            {observabilityLoading ? (
              <Skeleton className="h-40 w-full" />
            ) : !observability ? (
              <EmptyState title="Unavailable" description="The observability rollup could not be loaded." />
            ) : (
              <div className="space-y-4 text-sm">
                <div className="grid grid-cols-2 gap-2">
                  {(['workflow', 'execution', 'providers', 'cache', 'retention'] as const).map((k) => (
                    <div key={k} className="flex items-center justify-between rounded-lg border border-border px-3 py-2">
                      <span className="capitalize text-muted-foreground">{k}</span>
                      <Badge tone={healthTone(observability[k]?.health)}>{observability[k]?.health ?? '—'}</Badge>
                    </div>
                  ))}
                </div>
                {Object.keys(providerChain).length > 0 && (
                  <div>
                    <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Provider chain</p>
                    <div className="flex flex-wrap gap-2">
                      {Object.entries(providerChain).map(([name, h]) => (
                        <Badge key={name} tone={healthTone(h)}>
                          <span className="capitalize">{name}</span>: {h}
                        </Badge>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>

        {/* Data retention */}
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle className="flex items-center gap-2">
              <ShieldCheck className="h-4 w-4 text-muted-foreground" /> Data retention
            </CardTitle>
            <Button size="sm" variant="outline" onClick={() => runRetention.mutate()} loading={runRetention.isPending}>
              <Trash2 className="h-3.5 w-3.5" /> Run purge
            </Button>
          </CardHeader>
          <CardContent>
            {retention == null ? (
              <EmptyState
                title="Retention status unavailable"
                description="Requires an admin role. The retention scheduler purges aged audit/dead-letter ledgers when enabled."
              />
            ) : (
              <div className="space-y-3 text-sm">
                <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2">
                  <span className="text-muted-foreground">Scheduler</span>
                  <Badge tone={retention.enabled ? 'success' : 'neutral'}>
                    {retention.enabled ? 'enabled' : 'disabled (dark)'}
                  </Badge>
                </div>
                <p className="text-xs text-muted-foreground">
                  Purges <code>workflow_dead_letter</code>, <code>workflow_correlation</code> (terminal only),
                  and the recommendation / execution / resume-tailoring audit ledgers past their retention
                  windows. Deletes are recoverable only from a DB backup — choose windows before enabling.
                </p>
                {retention.purged && Object.keys(retention.purged).length > 0 && (
                  <div className="space-y-1">
                    {Object.entries(retention.purged).map(([table, n]) => (
                      <div key={table} className="flex items-center justify-between text-xs">
                        <span className="font-mono text-muted-foreground">{table}</span>
                        <span className="tabular-nums">{n}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Phase 4.1 — AI provider chain analytics + match-cache diagnostics. */}
      <ProviderAnalyticsPanel />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <CacheDiagnosticsPanel />
      </div>

      {/* Phase 4G — Workflow (3A) + Execution (2E) + resume-pipeline (2D) diagnostics matrix. */}
      <EngineHealthMatrix />

      {/* Phase 5.1D — Daily Discovery scheduler + provider health, with the manual trigger. */}
      <DailyDiscoveryHealthPanel />

      {/* Job Discovery: full 8-provider chain health (incl. Ashby/SmartRecruiters), circuit state. */}
      <JobProviderHealthPanel />

      {/* Phase 7 — Application Agent (autopilot) health + provider registry. */}
      <AutopilotHealthPanel />

      {/* Phase 7.11 — Application Package Intelligence: validation verdicts + generation metrics. */}
      <PackageIntelligencePanel />

      {/* Phase 7.12 — AI Review Pipeline: reviewer health, verdict + quality distribution. */}
      <ReviewPipelinePanel />

      {/* Phase 7.13 — Company Knowledge Graph: flags, knowledge/graph sizes, queue health. */}
      <CompanyIntelligencePanel />

      {/* Phase 7.15 — STAR Story Intelligence & Behavioral AI: flags, story/version counts, queue health. */}
      <StoryIntelligencePanel />

      {/* Phase 4G — Correlation Explorer, shared with the Workflow page. */}
      <WorkflowTraceExplorer />

      {/* Duplicate clusters */}
      <Card>
        <CardHeader>
          <CardTitle>Duplicate clusters</CardTitle>
        </CardHeader>
        <CardContent>
          {duplicatesLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : !duplicates || duplicates.topClusters.length === 0 ? (
            <EmptyState
              title="No duplicates detected yet"
              description="Enable jobs.dedup and run the dedup backfill to populate this. Detection only runs across jobs that already have an embedding."
            />
          ) : (
            <div className="space-y-3">
              <p className="text-xs text-muted-foreground">
                Same job posted to multiple boards, detected via embedding similarity + title/company match.
                Not yet filtered from Browse/Recommended — informational only.
              </p>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                      <th className="py-2 pr-4">Canonical posting</th>
                      <th className="py-2 pr-4">Company</th>
                      <th className="py-2 pr-4">Cluster size</th>
                    </tr>
                  </thead>
                  <tbody>
                    {duplicates.topClusters.map((c) => (
                      <tr key={c.canonicalJobId} className="border-b border-border/50">
                        <td className="py-2 pr-4 font-medium">{c.canonicalTitle}</td>
                        <td className="py-2 pr-4 text-muted-foreground">{c.canonicalCompany}</td>
                        <td className="py-2 pr-4 tabular-nums">{c.memberCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
