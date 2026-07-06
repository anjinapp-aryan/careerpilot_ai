import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip as RTooltip, XAxis, YAxis } from 'recharts';
import { Building2, Flame, Globe2, Layers, ListChecks, Sparkles, Star, Trophy } from 'lucide-react';
import { api } from '@/lib/api';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { Skeleton } from '@/components/ui/skeleton';
import type { DailyDiscoveryAnalyticsRow, DailyDiscoverySnapshot, DailyDiscoverySummary } from '@/types/workflow';

const CHART_COLORS = ['hsl(var(--primary))', 'hsl(var(--secondary))', 'hsl(var(--success))', 'hsl(var(--warning))', 'hsl(var(--danger))'];

function parseDistribution(raw?: string | null): { name: string; value: number }[] {
  if (!raw) return [];
  try {
    const obj = JSON.parse(raw) as Record<string, number>;
    return Object.entries(obj)
      .map(([name, value]) => ({ name, value }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 8);
  } catch {
    return [];
  }
}

function DistributionChart({ title, data }: { title: string; data: { name: string; value: number }[] }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        {data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No data yet.</p>
        ) : (
          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={data} layout="vertical" margin={{ left: 8, right: 16, top: 4, bottom: 4 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" horizontal={false} />
              <XAxis type="number" allowDecimals={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
              <YAxis type="category" dataKey="name" width={90} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
              <RTooltip cursor={{ fill: 'hsl(var(--muted))' }} />
              <Bar dataKey="value" radius={[0, 6, 6, 0]} maxBarSize={16}>
                {data.map((_, i) => (
                  <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * Phase 5.1A — Daily Discovery Intelligence section. Stat cards + distribution charts come from
 * the existing `GET /api/dashboard` snapshot's additive `dailyDiscovery` field (no new call);
 * the AI Summary panel and the daily trend chart make their own dark-tolerant calls to the two
 * new Phase 5 read endpoints. Renders nothing but a quiet empty card when the (dark-by-default)
 * agent has never run for this user.
 */
export function DailyDiscoveryPanel({ snapshot }: { snapshot?: DailyDiscoverySnapshot | null }) {
  const industry = useMemo(() => parseDistribution(snapshot?.industryDistribution), [snapshot]);
  const company = useMemo(() => parseDistribution(snapshot?.companyDistribution), [snapshot]);
  const skill = useMemo(() => parseDistribution(snapshot?.skillDistribution), [snapshot]);
  const matchStrength = useMemo(() => parseDistribution(snapshot?.matchStrengthDistribution), [snapshot]);

  const trend = useQuery<DailyDiscoveryAnalyticsRow[]>({
    queryKey: ['dashboard', 'daily-discovery-trend'],
    retry: false,
    staleTime: 30_000,
    queryFn: async () => {
      try {
        return (await api.get('/api/daily-discovery/analytics')).data;
      } catch {
        return [];
      }
    },
  });

  const summary = useQuery<DailyDiscoverySummary | null>({
    queryKey: ['dashboard', 'daily-discovery-summary'],
    retry: false,
    staleTime: 30_000,
    queryFn: async () => {
      try {
        return (await api.get('/api/daily-discovery/summary')).data;
      } catch {
        return null;
      }
    },
  });

  const trendRows = [...(trend.data ?? [])].reverse().slice(-14).map((r, i) => ({
    name: r.computedAt ? new Date(r.computedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : `Day ${i + 1}`,
    Recommended: r.recommendedJobs ?? 0,
    MustApply: r.mustApplyJobs ?? 0,
    HighPriority: r.highPriorityJobs ?? 0,
    AvgScore: r.averageScore ?? 0,
  }));

  if (!snapshot) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Daily Discovery Intelligence</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            The daily discovery agent hasn't run for your account yet — once it does, today's
            fetched, deduplicated, and classified jobs will appear here.
          </p>
        </CardContent>
      </Card>
    );
  }

  const topCompanies = (snapshot.topCompanies ?? summary.data?.topCompanies ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const topSkills = (snapshot.topSkills ?? summary.data?.topSkills ?? '').split(',').map((s) => s.trim()).filter(Boolean);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Sparkles className="h-4 w-4 text-primary" />
        <h2 className="text-base font-semibold text-foreground">Daily Discovery Intelligence</h2>
        {snapshot.computedAt && (
          <span className="text-xs text-muted-foreground">
            Last run {new Date(snapshot.computedAt).toLocaleString()}
          </span>
        )}
      </div>

      {/* Stat cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-3 xl:grid-cols-6">
        <KpiCard label="Recommended Jobs" value={snapshot.recommendedJobs ?? 0} icon={Sparkles} tone="primary" />
        <KpiCard label="Must Apply" value={snapshot.mustApplyJobs ?? 0} icon={Star} tone="danger" />
        <KpiCard label="High Priority" value={snapshot.highPriorityJobs ?? 0} icon={Flame} tone="warning" />
        <KpiCard label="Human Review" value={snapshot.humanReviewJobs ?? 0} icon={ListChecks} tone="info" />
        <KpiCard label="Domestic Jobs" value={snapshot.domesticJobs ?? 0} icon={Building2} tone="success" />
        <KpiCard label="International Jobs" value={snapshot.internationalJobs ?? 0} icon={Globe2} tone="info" />
      </div>

      {/* Distribution charts */}
      <div className="grid gap-4 md:grid-cols-2">
        <DistributionChart title="Match Strength Distribution" data={matchStrength} />
        <DistributionChart title="Industry Distribution" data={industry} />
        <DistributionChart title="Company Distribution" data={company} />
        <DistributionChart title="Skill Distribution" data={skill} />
      </div>

      {/* Trend */}
      <Card>
        <CardHeader className="flex-row items-center justify-between pb-2">
          <CardTitle className="text-base">Recommendation Trend</CardTitle>
          <Layers className="h-4 w-4 text-muted-foreground" />
        </CardHeader>
        <CardContent>
          {trend.isLoading ? (
            <Skeleton className="h-52 w-full" />
          ) : trendRows.length === 0 ? (
            <p className="text-sm text-muted-foreground">Not enough history yet — trends appear after a few daily runs.</p>
          ) : (
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={trendRows} margin={{ left: -20, right: 8, top: 8 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
                <XAxis dataKey="name" tickLine={false} axisLine={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
                <YAxis allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} width={32} />
                <RTooltip cursor={{ fill: 'hsl(var(--muted))' }} />
                <Bar dataKey="Recommended" fill="hsl(var(--primary))" radius={[4, 4, 0, 0]} maxBarSize={18} />
                <Bar dataKey="MustApply" fill="hsl(var(--danger))" radius={[4, 4, 0, 0]} maxBarSize={18} />
                <Bar dataKey="HighPriority" fill="hsl(var(--warning))" radius={[4, 4, 0, 0]} maxBarSize={18} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </CardContent>
      </Card>

      {/* AI Summary */}
      <Card className="border-primary/30 bg-primary/5">
        <CardHeader className="flex-row items-center gap-2 pb-2">
          <Trophy className="h-4 w-4 text-primary" />
          <CardTitle className="text-base">Today's AI Summary</CardTitle>
        </CardHeader>
        <CardContent>
          {summary.isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : !summary.data && !snapshot.summaryText ? (
            <p className="text-sm text-muted-foreground">No AI summary generated yet — enable the summary stage to see a daily briefing here.</p>
          ) : (
            <div className="space-y-3">
              <p className="whitespace-pre-line text-sm text-foreground">
                {summary.data?.summaryText ?? snapshot.summaryText}
              </p>
              <div className="flex flex-wrap gap-4 text-sm">
                {topCompanies.length > 0 && (
                  <div>
                    <p className="text-xs text-muted-foreground">Top companies</p>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      {topCompanies.slice(0, 5).map((c) => <Badge key={c} tone="primary">{c}</Badge>)}
                    </div>
                  </div>
                )}
                {topSkills.length > 0 && (
                  <div>
                    <p className="text-xs text-muted-foreground">Top skills</p>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      {topSkills.slice(0, 5).map((s) => <Badge key={s} tone="info">{s}</Badge>)}
                    </div>
                  </div>
                )}
                {(snapshot.interviewProbabilityDelta != null || snapshot.offerProbabilityDelta != null) && (
                  <div>
                    <p className="text-xs text-muted-foreground">Probability change</p>
                    <div className="mt-1 flex gap-1.5">
                      {snapshot.interviewProbabilityDelta != null && (
                        <Badge tone={snapshot.interviewProbabilityDelta >= 0 ? 'success' : 'danger'}>
                          Interview {snapshot.interviewProbabilityDelta >= 0 ? '+' : ''}{snapshot.interviewProbabilityDelta}%
                        </Badge>
                      )}
                      {snapshot.offerProbabilityDelta != null && (
                        <Badge tone={snapshot.offerProbabilityDelta >= 0 ? 'success' : 'danger'}>
                          Offer {snapshot.offerProbabilityDelta >= 0 ? '+' : ''}{snapshot.offerProbabilityDelta}%
                        </Badge>
                      )}
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
