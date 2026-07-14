import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Building2, Play, PlayCircle, Power, PowerOff, RefreshCw, ShieldAlert, Zap } from 'lucide-react';
import { api } from '@/lib/api';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { useToast } from '@/components/ui/toast';
import type {
  CompanyConnector,
  ConnectorStatistics,
  ConnectorSyncResult,
  EnterpriseAtsDiagnostics,
} from '@/types/workflow';

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

const ATS_TYPES = ['ALL', 'WORKDAY', 'TALEO', 'SUCCESSFACTORS'] as const;

/**
 * Phase 5.3.1, Parts 5+6 — Enterprise ATS admin surface: KPI row (Part 5's "ATS / Companies /
 * Enabled / Disabled / Healthy / Failed / Last Sync / Jobs Imported / Avg Latency / Failure %"
 * summary, reusing `KpiCard`) plus a full Company Connector management table (Part 6: search,
 * filter by ATS type, enable/disable, manual sync, last-failure detail) — reads/writes the new
 * `GET/POST /api/enterprise/connectors*` endpoints. Sibling to `JobProviderHealthPanel` (same
 * TanStack Query conventions), mounted into `AdminDashboard.tsx` alongside it rather than as a
 * separate route, matching every other admin panel in this file.
 */
export function EnterpriseConnectorPanel() {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [atsFilter, setAtsFilter] = useState<(typeof ATS_TYPES)[number]>('ALL');

  const diagnostics = useQuery<EnterpriseAtsDiagnostics | null>({
    queryKey: ['admin', 'enterprise', 'diagnostics'],
    queryFn: async () => {
      try {
        return (await api.get('/api/diagnostics/job-providers/enterprise')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const stats = useQuery<ConnectorStatistics | null>({
    queryKey: ['admin', 'enterprise', 'statistics'],
    queryFn: async () => {
      try {
        return (await api.get('/api/enterprise/connectors/statistics')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const connectors = useQuery<CompanyConnector[]>({
    queryKey: ['admin', 'enterprise', 'connectors'],
    queryFn: async () => {
      try {
        return (await api.get('/api/enterprise/connectors')).data ?? [];
      } catch {
        return [];
      }
    },
    retry: false,
    staleTime: 15_000,
  });

  function invalidateAll() {
    queryClient.invalidateQueries({ queryKey: ['admin', 'enterprise'] });
  }

  const toggle = useMutation({
    mutationFn: async ({ id, enable }: { id: string; enable: boolean }) =>
      (await api.post(`/api/enterprise/connectors/${id}/${enable ? 'enable' : 'disable'}`)).data,
    onSuccess: (_data, vars) => {
      toast({ title: vars.enable ? 'Connector enabled' : 'Connector disabled', variant: 'success' });
      invalidateAll();
    },
    onError: () => toast({ title: 'Action failed', variant: 'error' }),
  });

  const syncOne = useMutation({
    mutationFn: async (id: string) => (await api.post<ConnectorSyncResult>(`/api/enterprise/connectors/${id}/sync`)).data,
    onSuccess: (result) => {
      toast({
        title: result.success ? `Synced ${result.company}` : `Sync failed: ${result.company}`,
        description: result.success ? `${result.jobsImported} jobs imported` : result.error ?? undefined,
        variant: result.success ? 'success' : 'error',
      });
      invalidateAll();
    },
    onError: () => toast({ title: 'Sync failed', variant: 'error' }),
  });

  const syncAll = useMutation({
    mutationFn: async () => (await api.post<ConnectorSyncResult[]>('/api/enterprise/connectors/sync-all')).data,
    onSuccess: (results) => {
      const ok = results.filter((r) => r.success).length;
      toast({ title: `Sync all: ${ok}/${results.length} succeeded`, variant: ok === results.length ? 'success' : 'warning' });
      invalidateAll();
    },
    onError: () => toast({ title: 'Sync all failed', variant: 'error' }),
  });

  const syncFailed = useMutation({
    mutationFn: async () => (await api.post<ConnectorSyncResult[]>('/api/enterprise/connectors/sync-failed')).data,
    onSuccess: (results) => {
      toast({ title: `Re-synced ${results.length} failed connector(s)`, variant: 'default' });
      invalidateAll();
    },
    onError: () => toast({ title: 'Sync failed connectors failed', variant: 'error' }),
  });

  const rows = useMemo(() => {
    const list = connectors.data ?? [];
    return list.filter((c) => {
      if (atsFilter !== 'ALL' && c.atsType !== atsFilter) return false;
      if (search && !c.companyName.toLowerCase().includes(search.toLowerCase())) return false;
      return true;
    });
  }, [connectors.data, atsFilter, search]);

  const s = stats.data;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="flex items-center gap-2">
            <Building2 className="h-4 w-4 text-muted-foreground" /> Enterprise ATS Connectors
          </CardTitle>
          {diagnostics.data && (
            <Badge tone={diagnostics.data.masterEnabled ? 'success' : 'neutral'}>
              {diagnostics.data.masterEnabled ? 'Framework Enabled' : 'Framework Dark'}
            </Badge>
          )}
        </CardHeader>
        <CardContent>
          {stats.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : !s ? (
            <EmptyState title="Unavailable" description="Could not reach the enterprise connector statistics endpoint." />
          ) : (
            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
              <KpiCard label="Companies" value={s.total} icon={Building2} tone="primary" />
              <KpiCard label="Enabled" value={s.enabled} icon={Power} tone="success" hint={`${s.disabled} disabled`} />
              <KpiCard label="Healthy" value={s.healthy} icon={Zap} tone="success" hint={`${s.failed} failed`} />
              <KpiCard label="Jobs Imported" value={s.jobsImportedTotal} icon={RefreshCw} tone="info" hint={`avg ${s.averageJobsPerConnector.toFixed(1)}/connector`} />
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between flex-wrap gap-2">
          <CardTitle className="text-base">Company Connectors</CardTitle>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" onClick={() => syncFailed.mutate()} disabled={syncFailed.isPending}>
              <ShieldAlert className="mr-1.5 h-3.5 w-3.5" /> Sync Failed
            </Button>
            <Button size="sm" onClick={() => syncAll.mutate()} disabled={syncAll.isPending}>
              <PlayCircle className="mr-1.5 h-3.5 w-3.5" /> Sync All
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Input
              placeholder="Search company…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="max-w-xs"
            />
            <div className="flex gap-1">
              {ATS_TYPES.map((t) => (
                <Button
                  key={t}
                  size="sm"
                  variant={atsFilter === t ? 'primary' : 'outline'}
                  onClick={() => setAtsFilter(t)}
                >
                  {t}
                </Button>
              ))}
            </div>
          </div>

          {connectors.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : rows.length === 0 ? (
            <EmptyState title="No connectors" description="No Enterprise ATS connectors match this filter, or none are configured yet." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th className="py-2 pr-4">Company</th>
                    <th className="py-2 pr-4">ATS</th>
                    <th className="py-2 pr-4">Enabled</th>
                    <th className="py-2 pr-4">Health</th>
                    <th className="py-2 pr-4">Jobs Imported</th>
                    <th className="py-2 pr-4">Avg Latency</th>
                    <th className="py-2 pr-4">Last Sync</th>
                    <th className="py-2 pr-4">Last Failure</th>
                    <th className="py-2 pr-4">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((c) => (
                    <tr key={c.id} className="border-b border-border/50 align-top">
                      <td className="py-2 pr-4 font-medium">{c.companyName}</td>
                      <td className="py-2 pr-4 text-xs text-muted-foreground">{c.atsType}</td>
                      <td className="py-2 pr-4">
                        <Badge tone={c.enabled ? 'success' : 'neutral'}>{c.enabled ? 'Yes' : 'No'}</Badge>
                      </td>
                      <td className="py-2 pr-4"><Badge tone={healthTone(c.healthStatus)}>{c.healthStatus}</Badge></td>
                      <td className="py-2 pr-4 tabular-nums">{c.jobsImported}</td>
                      <td className="py-2 pr-4 tabular-nums text-muted-foreground">{c.averageLatencyMs}ms</td>
                      <td className="py-2 pr-4 text-xs text-muted-foreground">
                        {c.lastSuccessfulSync ? new Date(c.lastSuccessfulSync).toLocaleString() : '—'}
                      </td>
                      <td className="py-2 pr-4 max-w-[220px] truncate text-xs text-danger" title={c.lastFailureReason ?? ''}>
                        {c.lastFailureReason ?? '—'}
                      </td>
                      <td className="py-2 pr-4">
                        <div className="flex items-center gap-1.5">
                          <Button
                            size="icon"
                            variant="outline"
                            title="Manual sync"
                            onClick={() => syncOne.mutate(c.id)}
                            disabled={syncOne.isPending}
                          >
                            <Play className="h-3.5 w-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="outline"
                            title={c.enabled ? 'Disable' : 'Enable'}
                            onClick={() => toggle.mutate({ id: c.id, enable: !c.enabled })}
                            disabled={toggle.isPending}
                          >
                            {c.enabled ? <PowerOff className="h-3.5 w-3.5" /> : <Power className="h-3.5 w-3.5" />}
                          </Button>
                        </div>
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
