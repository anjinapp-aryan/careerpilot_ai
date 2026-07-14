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
import { Brain, Briefcase, Building2, GraduationCap, Globe2, Map as MapIcon, Sparkles, Target, TrendingUp } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { CompanyIntelligenceWidget } from '@/components/dashboard/CompanyIntelligenceWidget';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import type { ApplicationAnalyticsRow, CareerIntelligenceRow, CareerLearningView } from '@/types/workflow';

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

  const learning = useQuery<CareerLearningView | null>({
    queryKey: ['career-intelligence', 'learning'],
    queryFn: async () => {
      try {
        return (await api.get('/api/workflow/career-learning')).data;
      } catch {
        return null;
      }
    },
    retry: false,
  });

  const rows = career.data ?? [];
  const analyticsRows = analytics.data ?? [];
  const learningView = learning.data;
  const hasLearning = !!learningView && (!!learningView.strategy || learningView.topCompanies.length > 0
    || learningView.topSkills.length > 0);
  const isEmpty = !career.isLoading && rows.length === 0 && !analytics.isLoading && analyticsRows.length === 0
    && !learning.isLoading && !hasLearning;

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

          {/* Phase 6.5 — Learned career trend (Adaptive Career Engine), additive to the deterministic
              engine above; empty/absent unless learning.adaptive-career.enabled is on. */}
          <Card>
            <CardHeader className="flex-row items-center gap-2">
              <GraduationCap className="h-4 w-4 text-muted-foreground" />
              <CardTitle className="text-base">Career learning trend</CardTitle>
            </CardHeader>
            <CardContent>
              {learning.isLoading ? (
                <Skeleton className="h-24 w-full" />
              ) : !hasLearning ? (
                <p className="text-sm text-muted-foreground">
                  Not enough learned history yet — this builds up from your tracked application outcomes.
                </p>
              ) : (
                <div className="space-y-4">
                  {learningView!.strategy && (
                    <div className="grid gap-4 sm:grid-cols-3">
                      <div>
                        <p className="text-xs text-muted-foreground">Learned career success</p>
                        <p className="text-2xl font-semibold tabular-nums text-foreground">
                          {pct(learningView!.strategy.careerSuccessProbability)}%
                        </p>
                      </div>
                      <div>
                        <p className="text-xs text-muted-foreground">Learned growth probability</p>
                        <p className="text-2xl font-semibold tabular-nums text-foreground">
                          {pct(learningView!.strategy.careerGrowthProbability)}%
                        </p>
                      </div>
                      <div>
                        <p className="text-xs text-muted-foreground">Market demand score</p>
                        <p className="text-2xl font-semibold tabular-nums text-foreground">
                          {pct(learningView!.strategy.marketDemandScore)}%
                        </p>
                      </div>
                    </div>
                  )}
                  {learningView!.strategy?.recommendedTrajectory && (
                    <p className="rounded-lg border border-border bg-muted/30 px-3 py-2.5 text-sm text-foreground">
                      {learningView!.strategy.recommendedTrajectory}
                    </p>
                  )}
                  <div className="grid gap-4 sm:grid-cols-2">
                    {learningView!.topCompanies.length > 0 && (
                      <div>
                        <p className="mb-1.5 text-xs font-medium text-muted-foreground">Best-performing companies</p>
                        <div className="flex flex-wrap gap-1.5">
                          {learningView!.topCompanies.slice(0, 6).map((c, i) => (
                            <Badge key={i} tone="success">{c.dimensionKey}</Badge>
                          ))}
                        </div>
                      </div>
                    )}
                    {learningView!.topSkills.length > 0 && (
                      <div>
                        <p className="mb-1.5 text-xs font-medium text-muted-foreground">Best-performing skills</p>
                        <div className="flex flex-wrap gap-1.5">
                          {learningView!.topSkills.slice(0, 6).map((s, i) => (
                            <Badge key={i} tone="primary">{s.dimensionKey}</Badge>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}

      {/* Gap C — Career Coach roadmap persistence. Additive section, independent of the isEmpty
          gate above: it renders its own empty state when no roadmap has been persisted yet
          (career.roadmap.persistence.enabled off, or no workflow run has completed since it was
          turned on), reusing the same GET /api/workflow/career-learning response. */}
      {(() => {
        const strategy = learningView?.strategy;
        const hasRoadmap = !!strategy && (!!strategy.roadmap3Month || !!strategy.roadmap6Month
          || !!strategy.roadmap12Month || !!strategy.skillGapsJson);
        let skillGaps: string[] = [];
        if (strategy?.skillGapsJson) {
          try {
            const parsed = JSON.parse(strategy.skillGapsJson);
            if (Array.isArray(parsed)) skillGaps = parsed.filter((s): s is string => typeof s === 'string');
          } catch {
            skillGaps = [];
          }
        }
        return (
          <Card>
            <CardHeader className="flex-row items-center gap-2">
              <MapIcon className="h-4 w-4 text-muted-foreground" />
              <CardTitle className="text-base">Career roadmap</CardTitle>
            </CardHeader>
            <CardContent>
              {learning.isLoading ? (
                <Skeleton className="h-24 w-full" />
              ) : !hasRoadmap ? (
                <p className="text-sm text-muted-foreground">
                  No roadmap yet — this is generated by the AI workflow's Career Strategy agent once
                  a workflow run completes.
                </p>
              ) : (
                <div className="space-y-4">
                  <div className="grid gap-4 sm:grid-cols-3">
                    {[
                      { label: 'Next 3 months', value: strategy?.roadmap3Month },
                      { label: 'Next 6 months', value: strategy?.roadmap6Month },
                      { label: 'Next 12 months', value: strategy?.roadmap12Month },
                    ].map(({ label, value }) => (
                      <div key={label}>
                        <p className="mb-1.5 text-xs font-medium text-muted-foreground">{label}</p>
                        {value ? (
                          <p className="whitespace-pre-line rounded-lg border border-border bg-muted/30 px-3 py-2.5 text-sm text-foreground">
                            {value}
                          </p>
                        ) : (
                          <p className="text-sm text-muted-foreground">—</p>
                        )}
                      </div>
                    ))}
                  </div>
                  {skillGaps.length > 0 && (
                    <div>
                      <p className="mb-1.5 text-xs font-medium text-muted-foreground">Skill gaps to close</p>
                      <div className="flex flex-wrap gap-1.5">
                        {skillGaps.map((s, i) => (
                          <Badge key={i} tone="warning">{s}</Badge>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </CardContent>
          </Card>
        );
      })()}

      {/* Phase 7.13 — Company / Technology / Industry map from the knowledge graph (dark-safe). */}
      <CompanyIntelligenceWidget />
    </div>
  );
}
