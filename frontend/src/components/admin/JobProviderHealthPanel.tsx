import { useQuery } from '@tanstack/react-query';
import { Globe2, RefreshCw, ShieldAlert, Timer } from 'lucide-react';
import { api } from '@/lib/api';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { KpiCard } from '@/components/dashboard/KpiCard';
import type { JobDiscoverySchedulerSummary, JobProviderHealthResponse } from '@/types/workflow';

const HEALTH_TONE: Record<string, BadgeTone> = {
  UP: 'success',
  NEVER_RUN: 'neutral',
  DEGRADED: 'warning',
  DOWN: 'danger',
  NOT_CONFIGURED: 'neutral',
};

const CIRCUIT_TONE: Record<string, BadgeTone> = {
  CLOSED: 'success',
  HALF_OPEN: 'warning',
  OPEN: 'danger',
};

function healthTone(v?: string | null): BadgeTone {
  return HEALTH_TONE[(v ?? '').toUpperCase()] ?? 'neutral';
}

function circuitTone(v?: string | null): BadgeTone {
  return CIRCUIT_TONE[(v ?? '').toUpperCase()] ?? 'neutral';
}

const PROVIDER_LABELS: Record<string, string> = {
  greenhouse: 'Greenhouse',
  lever: 'Lever',
  ashby: 'Ashby',
  smartrecruiters: 'SmartRecruiters',
  remoteok: 'RemoteOK',
  arbeitnow: 'Arbeitnow',
  adzuna: 'Adzuna',
  jooble: 'Jooble',
};

/**
 * Job-discovery provider observability panel — reads the new `JobDiscoveryHealthTracker`-backed
 * diagnostics (`GET /api/diagnostics/job-providers/providers` + `/discovery`). Sibling to
 * `DailyDiscoveryHealthPanel` (same TanStack Query / `retry:false` / `staleTime` conventions,
 * same no-auth counts-only endpoint shape) rather than a parallel dashboard — this one covers the
 * full 8-provider `JobAggregationService` chain (including the 2 new providers, Ashby and
 * SmartRecruiters), which the daily-discovery panel above does not report on.
 */
export function JobProviderHealthPanel() {
  const discovery = useQuery<JobDiscoverySchedulerSummary | null>({
    queryKey: ['admin', 'job-providers', 'discovery'],
    queryFn: async () => {
      try {
        return (await api.get('/api/diagnostics/job-providers/discovery')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const providers = useQuery<JobProviderHealthResponse | null>({
    queryKey: ['admin', 'job-providers', 'providers'],
    queryFn: async () => {
      try {
        return (await api.get('/api/diagnostics/job-providers/providers')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const d = discovery.data;
  const rows = providers.data ? Object.entries(providers.data) : [];

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="flex items-center gap-2">
            <Globe2 className="h-4 w-4 text-muted-foreground" /> Job Provider Health
          </CardTitle>
        </CardHeader>
        <CardContent>
          {discovery.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : !d ? (
            <EmptyState title="Unavailable" description="Could not reach the job-provider diagnostics endpoint." />
          ) : (
            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
              <KpiCard label="Daily Scheduler" value={d.dailySchedulerEnabled ? 'Enabled' : 'Disabled'} icon={RefreshCw} tone={d.dailySchedulerEnabled ? 'success' : 'warning'} />
              <KpiCard label="Hourly Scheduler" value={d.hourlySchedulerEnabled ? 'Enabled' : 'Disabled'} icon={Timer} tone={d.hourlySchedulerEnabled ? 'success' : 'primary'} />
              <KpiCard label="Providers Configured" value={`${d.providersConfigured}/${d.providersRegistered}`} icon={Globe2} tone="primary" />
              <KpiCard label="Recent Failed Runs" value={d.recentFailedRuns} icon={ShieldAlert} tone={d.recentFailedRuns > 0 ? 'danger' : 'success'} hint={`${d.recentJobsImported} imported of ${d.recentJobsFetched} fetched`} />
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Provider Matrix (8 sources)</CardTitle>
        </CardHeader>
        <CardContent>
          {providers.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : rows.length === 0 ? (
            <EmptyState title="Unavailable" description="Could not reach the job-provider diagnostics endpoint." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th className="py-2 pr-4">Provider</th>
                    <th className="py-2 pr-4">Configured</th>
                    <th className="py-2 pr-4">Health</th>
                    <th className="py-2 pr-4">Circuit</th>
                    <th className="py-2 pr-4">Success Rate</th>
                    <th className="py-2 pr-4">Avg Latency</th>
                    <th className="py-2 pr-4">Last Fetched</th>
                    <th className="py-2 pr-4">Last Run</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map(([key, p]) => (
                    <tr key={key} className="border-b border-border/50">
                      <td className="py-2 pr-4 font-medium">{PROVIDER_LABELS[key] ?? key}</td>
                      <td className="py-2 pr-4">
                        <Badge tone={p.configured ? 'success' : 'neutral'}>{p.configured ? 'Yes' : 'No'}</Badge>
                      </td>
                      <td className="py-2 pr-4"><Badge tone={healthTone(p.health)}>{p.health}</Badge></td>
                      <td className="py-2 pr-4"><Badge tone={circuitTone(p.circuitState)}>{p.circuitState}</Badge></td>
                      <td className="py-2 pr-4 tabular-nums">{(p.successRate * 100).toFixed(0)}%</td>
                      <td className="py-2 pr-4 tabular-nums text-muted-foreground">{p.avgLatencyMs}ms</td>
                      <td className="py-2 pr-4 tabular-nums">{p.lastJobsFetched ?? '—'}</td>
                      <td className="py-2 pr-4 text-xs text-muted-foreground">
                        {p.lastRunAt ? new Date(p.lastRunAt).toLocaleString() : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
