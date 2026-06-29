import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip as RTooltip, XAxis, YAxis } from 'recharts';
import { Activity, Copy, Database, Globe2, RefreshCw, Sparkles, Wand2 } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { useToast } from '@/components/ui/toast';

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
