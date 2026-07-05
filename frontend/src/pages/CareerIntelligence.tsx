import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip as RTooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { Brain, Briefcase, Building2, Globe2, Sparkles, Target, TrendingUp } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import type { ApplicationAnalyticsRow, CareerIntelligenceRow } from '@/types/workflow';

const PROBABILITY_DIMENSIONS: { key: string; label: string; icon: typeof Brain }[] = [
  { key: 'CAREER_SUCCESS', label: 'Career Success Probability', icon: Brain },
  { key: 'INTERVIEW_PROBABILITY', label: 'Interview Probability', icon: Sparkles },
  { key: 'OFFER_PROBABILITY', label: 'Offer Probability', icon: Target },
];

const GROUPED_DIMENSIONS: { key: string; label: string; icon: typeof Globe2 }[] = [
  { key: 'COUNTRY_SUCCESS', label: 'Top countries by success rate', icon: Globe2 },
  { key: 'COMPANY_SUCCESS', label: 'Top companies by success rate', icon: Building2 },
  { key: 'TECHNOLOGY_SUCCESS', label: 'Top technologies by success rate', icon: Sparkles },
  { key: 'ROLE_SUCCESS', label: 'Top roles by success rate', icon: Briefcase },
];

function pct(v?: number | null): number {
  return Math.round((v ?? 0) * 100);
}

/**
 * Phase 4F — Career Intelligence workspace. Reads the Phase 3A.6 Career Intelligence engine
 * (GET /api/workflow/career-intelligence) and the analytics engine (GET /api/workflow/analytics).
 * Both ship dark by default (CAREER_INTELLIGENCE_ENABLED / WORKFLOW_ANALYTICS_ENABLED = false),
 * so an empty response renders as "not enough data yet" rather than an error — no chart is drawn
 * from fabricated numbers.
 */
export default function CareerIntelligence() {
  const career = useQuery<CareerIntelligenceRow[]>({
    queryKey: ['career-intelligence'],
    queryFn: async () => {
      try {
        return (await api.get('/api/workflow/career-intelligence')).data;
      } catch {
        return [];
      }
    },
    retry: false,
  });

  const analytics = useQuery<ApplicationAnalyticsRow[]>({
    queryKey: ['career-intelligence', 'analytics'],
    queryFn: async () => {
      try {
        return (await api.get('/api/workflow/analytics')).data;
      } catch {
        return [];
      }
    },
    retry: false,
  });

  const rows = career.data ?? [];
  const analyticsRows = analytics.data ?? [];
  const isEmpty = !career.isLoading && rows.length === 0 && !analytics.isLoading && analyticsRows.length === 0;

  const byDimension = useMemo(() => {
    const m = new Map<string, CareerIntelligenceRow[]>();
    for (const r of rows) {
      const list = m.get(r.dimension) ?? [];
      list.push(r);
      m.set(r.dimension, list);
    }
    return m;
  }, [rows]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Career Intelligence"
        description="Deterministic, learned probabilities computed from your application outcomes — no LLM involved."
      />

      {isEmpty ? (
        <EmptyState
          icon={Brain}
          title="Not enough data yet"
          description="Career Intelligence learns from your tracked application outcomes. It ships disabled by default and populates once the workflow engine has enough completed applications to compute probabilities from."
        />
      ) : (
        <>
          {/* Top-line probability cards */}
          <div className="grid gap-4 sm:grid-cols-3">
            {PROBABILITY_DIMENSIONS.map(({ key, label, icon: Icon }) => {
              const row = byDimension.get(key)?.[0];
              return (
                <Card key={key}>
                  <CardHeader className="flex-row items-center justify-between pb-2">
                    <CardTitle className="text-sm">{label}</CardTitle>
                    <Icon className="h-4 w-4 text-muted-foreground" />
                  </CardHeader>
                  <CardContent>
                    {career.isLoading ? (
                      <Skeleton className="h-10 w-full" />
                    ) : row ? (
                      <div className="flex items-baseline gap-2">
                        <span className="text-3xl font-semibold tabular-nums text-foreground">{pct(row.probability)}%</span>
                        {row.sampleSize != null && (
                          <span className="text-xs text-muted-foreground">n={row.sampleSize}</span>
                        )}
                      </div>
                    ) : (
                      <p className="text-sm text-muted-foreground">Not enough data yet.</p>
                    )}
                  </CardContent>
                </Card>
              );
            })}
          </div>

          {/* Grouped dimension charts */}
          <div className="grid gap-4 lg:grid-cols-2">
            {GROUPED_DIMENSIONS.map(({ key, label, icon: Icon }) => {
              const group = (byDimension.get(key) ?? [])
                .filter((r) => r.dimensionKey)
                .sort((a, b) => (b.probability ?? 0) - (a.probability ?? 0))
                .slice(0, 8)
                .map((r) => ({ name: r.dimensionKey as string, value: pct(r.probability) }));
              return (
                <Card key={key}>
                  <CardHeader className="flex-row items-center gap-2 pb-2">
                    <Icon className="h-4 w-4 text-muted-foreground" />
                    <CardTitle className="text-sm">{label}</CardTitle>
                  </CardHeader>
                  <CardContent>
                    {career.isLoading ? (
                      <Skeleton className="h-40 w-full" />
                    ) : group.length === 0 ? (
                      <p className="text-sm text-muted-foreground">Not enough data yet.</p>
                    ) : (
                      <div style={{ height: Math.max(160, group.length * 32) }}>
                        <ResponsiveContainer width="100%" height="100%">
                          <BarChart data={group} layout="vertical" margin={{ left: 24 }}>
                            <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                            <XAxis type="number" domain={[0, 100]} tickFormatter={(v) => `${v}%`} />
                            <YAxis type="category" dataKey="name" width={110} interval={0} />
                            <RTooltip formatter={(v) => `${v}%`} />
                            <Bar dataKey="value" radius={[0, 4, 4, 0]} fill="hsl(var(--primary))" />
                          </BarChart>
                        </ResponsiveContainer>
                      </div>
                    )}
                  </CardContent>
                </Card>
              );
            })}
          </div>

          {/* Application outcome analytics */}
          <Card>
            <CardHeader className="flex-row items-center gap-2">
              <TrendingUp className="h-4 w-4 text-muted-foreground" />
              <CardTitle className="text-base">Application analytics</CardTitle>
            </CardHeader>
            <CardContent>
              {analytics.isLoading ? (
                <Skeleton className="h-24 w-full" />
              ) : analyticsRows.length === 0 ? (
                <p className="text-sm text-muted-foreground">No computed analytics yet.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                        <th className="py-2 pr-4">Metric</th>
                        <th className="py-2 pr-4">Dimension</th>
                        <th className="py-2 pr-4">Value</th>
                        <th className="py-2 pr-4">Computed</th>
                      </tr>
                    </thead>
                    <tbody>
                      {analyticsRows.map((r, i) => (
                        <tr key={`${r.metric}-${r.dimensionKey}-${i}`} className="border-b border-border/50">
                          <td className="py-2 pr-4 font-medium">{r.metric}</td>
                          <td className="py-2 pr-4 text-muted-foreground">{r.dimensionKey || '—'}</td>
                          <td className="py-2 pr-4 tabular-nums">
                            <Badge tone="primary">{r.value != null ? r.value : '—'}</Badge>
                          </td>
                          <td className="py-2 pr-4 text-muted-foreground">
                            {r.computedAt ? new Date(r.computedAt).toLocaleDateString() : '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
