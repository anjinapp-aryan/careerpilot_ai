import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarClock, Play, RefreshCw, Timer, Users } from 'lucide-react';
import { api } from '@/lib/api';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { useToast } from '@/components/ui/toast';
import type { DailyDiscoveryProvidersResponse, DailyDiscoverySchedulerHealth } from '@/types/workflow';

const HEALTH_TONE: Record<string, BadgeTone> = {
  UP: 'success',
  NEVER_RUN: 'neutral',
  DEGRADED: 'warning',
  DOWN: 'danger',
  NOT_CONFIGURED: 'neutral',
};

function healthTone(v?: string | null): BadgeTone {
  return HEALTH_TONE[(v ?? '').toUpperCase()] ?? 'neutral';
}

function latencyMs(startedAt?: string | null, finishedAt?: string | null): number | null {
  if (!startedAt || !finishedAt) return null;
  const ms = new Date(finishedAt).getTime() - new Date(startedAt).getTime();
  return Number.isFinite(ms) && ms >= 0 ? ms : null;
}

const PROVIDER_LABELS: Record<string, string> = {
  greenhouse: 'Greenhouse',
  lever: 'Lever',
  remoteok: 'RemoteOK',
  wellfound: 'Wellfound',
  companyCareerSites: 'Company Sites',
};

/**
 * Phase 5.1D — Daily Discovery Health. Reads the two Phase 5 diagnostics endpoints (no-auth,
 * counts-only, same convention as every other admin diagnostics panel) and exposes the manual
 * trigger. Note: this diagnostics endpoint reports Greenhouse/Lever/RemoteOK/Wellfound/Company
 * Sites only — Arbeitnow/Adzuna/Jooble aren't included in `GET /api/diagnostics/daily-discovery/providers`
 * (existing backend, unmodified this pass), so they aren't shown here rather than fabricated.
 */
export function DailyDiscoveryHealthPanel() {
  const qc = useQueryClient();
  const { toast } = useToast();

  const scheduler = useQuery<DailyDiscoverySchedulerHealth | null>({
    queryKey: ['admin', 'daily-discovery', 'scheduler'],
    queryFn: async () => {
      try {
        return (await api.get('/api/diagnostics/daily-discovery')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const providers = useQuery<DailyDiscoveryProvidersResponse | null>({
    queryKey: ['admin', 'daily-discovery', 'providers'],
    queryFn: async () => {
      try {
        return (await api.get('/api/diagnostics/daily-discovery/providers')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const trigger = useMutation({
    mutationFn: async () => (await api.post('/api/daily-discovery/run')).data as { status: string; runId?: string },
    onSuccess: (res) => {
      if (res.status === 'NOT_ENABLED') {
        toast({ variant: 'default', title: 'Scheduler disabled', description: 'career.discovery.scheduler.enabled is off.' });
      } else {
        toast({ variant: 'success', title: 'Daily discovery started', description: res.runId ? `Run ${res.runId.slice(0, 8)}…` : undefined });
      }
      qc.invalidateQueries({ queryKey: ['admin', 'daily-discovery'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not start run' }),
  });

  const s = scheduler.data;
  const rows = providers.data
    ? (Object.entries(providers.data) as [string, DailyDiscoveryProvidersResponse[keyof DailyDiscoveryProvidersResponse]][])
    : [];

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="flex items-center gap-2">
            <CalendarClock className="h-4 w-4 text-muted-foreground" /> Daily Discovery Health
          </CardTitle>
          <div className="flex items-center gap-2">
            {s && <Badge tone={healthTone(s.health)}>{s.health}</Badge>}
            <Button size="sm" onClick={() => trigger.mutate()} loading={trigger.isPending}>
              <Play className="h-3.5 w-3.5" /> Run Daily Discovery
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {scheduler.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : !s ? (
            <EmptyState title="Unavailable" description="Could not reach the daily discovery diagnostics endpoint." />
          ) : (
            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
              <KpiCard label="Scheduler" value={s.schedulerEnabled ? 'Enabled' : 'Disabled'} icon={CalendarClock} tone={s.schedulerEnabled ? 'success' : 'warning'} />
              <KpiCard label="Last Run" value={s.lastStatus} icon={RefreshCw} tone={s.lastStatus === 'SUCCESS' ? 'success' : s.lastStatus === 'FAILED' ? 'danger' : 'primary'} hint={s.lastRunAt ? new Date(s.lastRunAt).toLocaleString() : 'Never run'} />
              <KpiCard label="Duration" value={s.lastDurationMs} suffix="ms" icon={Timer} tone="info" />
              <KpiCard label="Users Processed" value={s.usersProcessed} icon={Users} tone="primary" hint={`${s.failedRuns} failed of ${s.totalRuns} runs`} />
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Provider Health Matrix</CardTitle>
        </CardHeader>
        <CardContent>
          {providers.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : rows.length === 0 ? (
            <EmptyState title="Unavailable" description="Could not reach the provider diagnostics endpoint." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th className="py-2 pr-4">Provider</th>
                    <th className="py-2 pr-4">Configured</th>
                    <th className="py-2 pr-4">Health</th>
                    <th className="py-2 pr-4">Last Fetch</th>
                    <th className="py-2 pr-4">Latency</th>
                    <th className="py-2 pr-4">Jobs Fetched</th>
                    <th className="py-2 pr-4">Notes</th>
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
                      <td className="py-2 pr-4 text-xs text-muted-foreground">
                        {p.lastStartedAt ? new Date(p.lastStartedAt).toLocaleString() : '—'}
                      </td>
                      <td className="py-2 pr-4 tabular-nums text-muted-foreground">
                        {(() => {
                          const ms = latencyMs(p.lastStartedAt, p.lastFinishedAt);
                          return ms != null ? `${ms}ms` : '—';
                        })()}
                      </td>
                      <td className="py-2 pr-4 tabular-nums">{p.lastJobsFetched ?? '—'}</td>
                      <td className="py-2 pr-4 text-xs text-muted-foreground">{p.reason ?? '—'}</td>
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
