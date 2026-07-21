import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Activity,
  BarChart3,
  Brain,
  Check,
  ChevronDown,
  ChevronUp,
  Clock,
  Download,
  Globe2,
  Mic,
  Pencil,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  ThumbsDown,
  Wrench,
} from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { Dialog, DialogBody, DialogFooter, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { useToast } from '@/components/ui/toast';
import type {
  CandidateProfileDto,
  CareerDecisionMemory,
  CareerMemoryDiagnostics,
  CareerMemorySummary,
  EditMemoryRequest,
  InterviewDto,
} from '@/types/workflow';

function pct(v?: number | null): string {
  if (v == null) return '—';
  return `${Math.round(v * 100)}%`;
}

function relativeTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso).getTime();
  if (Number.isNaN(d)) return '—';
  const diffMs = Date.now() - d;
  const mins = Math.round(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  return `${days}d ago`;
}

function confidenceTone(v?: number | null): BadgeTone {
  if (v == null) return 'neutral';
  if (v >= 0.85) return 'success';
  if (v >= 0.6) return 'warning';
  return 'danger';
}

/** Human label for a category enum value, e.g. "WORK_MODE" -> "Work mode". */
function categoryLabel(c: string): string {
  return c.charAt(0) + c.slice(1).toLowerCase().replace(/_/g, ' ');
}

const EDITABLE_CATEGORIES = [
  'COUNTRY', 'SALARY', 'TECHNOLOGY', 'CAREER', 'COMPANY', 'LEARNING',
  'WORK_MODE', 'COMPANY_SIZE', 'INDUSTRY', 'PREFERENCE',
];

/**
 * Recommendation Impact — stated HONESTLY against what `CareerMemoryBooster` (Phase 7.15.1/
 * 7.15.2 backend) actually does, not a fabricated description. Verified by reading the booster's
 * source directly: it only reacts to (a) TECHNOLOGY-category memories matched against a job's
 * title/skills text, and (b) APPLICATION-category JOB_REJECTED/JOB_APPROVED/JOB_SAVED memories
 * matched against a job's company name — both ±5 points max, chained after the company-knowledge
 * and learning boosts in `JobMatchingService.scoreV2`. Every OTHER category (COUNTRY, SALARY,
 * CAREER, INDUSTRY, etc.) currently has ZERO effect on job ranking — there is no country/salary/
 * career boosting logic anywhere in this codebase yet. Claiming otherwise here would actively
 * undermine the "increase trust" goal of this whole dashboard, so this function says so plainly
 * instead of inventing plausible-sounding impact bullets.
 */
function recommendationImpact(m: CareerDecisionMemory): { real: boolean; lines: string[] } {
  const positive = m.decisionType.endsWith('_POSITIVE') || m.decisionType.startsWith('JOB_APPROVED') || m.decisionType.startsWith('JOB_SAVED');
  const negative = m.decisionType.endsWith('_NEGATIVE') || m.decisionType.startsWith('JOB_REJECTED');

  if (m.category === 'TECHNOLOGY') {
    return {
      real: true,
      lines: positive
        ? [`Nudges a job's match score up (max +2) whenever "${m.value}" appears in that job's title or skills.`]
        : negative
          ? [`Nudges a job's match score down (max -2) whenever "${m.value}" appears in that job's title or skills.`]
          : ['Recorded, but neither clearly positive nor negative — no score effect yet.'],
    };
  }
  if (m.category === 'APPLICATION' && (m.decisionType.startsWith('JOB_REJECTED') || m.decisionType.startsWith('JOB_APPROVED') || m.decisionType.startsWith('JOB_SAVED'))) {
    return {
      real: true,
      lines: m.decisionType.startsWith('JOB_REJECTED')
        ? ['Lowers the match score (max -3) for other jobs at this company.']
        : m.decisionType.startsWith('JOB_APPROVED')
          ? ['Raises the match score (max +2) for other jobs at this company.']
          : ['Raises the match score (max +1) for other jobs at this company.'],
    };
  }
  return {
    real: false,
    lines: ['Not yet used in job ranking — only technology preferences and company approve/reject decisions currently affect match scores.'],
  };
}

/** Category-scoped fields the "Edit Memory" dialog offers — kept in sync with EDITABLE_CATEGORIES. */
function EditMemoryDialog({
  open,
  onOpenChange,
  onSubmit,
  pending,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (req: EditMemoryRequest) => void;
  pending: boolean;
}) {
  const [category, setCategory] = useState('COUNTRY');
  const [value, setValue] = useState('');
  const [reason, setReason] = useState('');

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogHeader onClose={() => onOpenChange(false)}>
        <DialogTitle>Tell the AI something</DialogTitle>
        <DialogDescription>
          This writes a new, fully-verified memory — it never erases what the AI knew before, but this
          takes priority going forward.
        </DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-3">
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Category</label>
          <select
            className="flex h-10 w-full rounded-lg border border-input bg-card px-3 py-2 text-sm text-foreground shadow-xs"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
          >
            {EDITABLE_CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {categoryLabel(c)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Value</label>
          <Input placeholder="e.g. Germany, €120K, Kubernetes" value={value} onChange={(e) => setValue(e.target.value)} />
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Reason (optional)</label>
          <Input placeholder="Why?" value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
      </DialogBody>
      <DialogFooter>
        <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
          Cancel
        </Button>
        <Button
          size="sm"
          loading={pending}
          disabled={!value.trim()}
          onClick={() => onSubmit({ category, value: value.trim(), reason: reason.trim() || undefined })}
        >
          Save
        </Button>
      </DialogFooter>
    </Dialog>
  );
}

/**
 * Phase 7.15.1-7.15.3A — the trust interface between the user and every AI Memory subsystem in
 * this codebase. Every data source is reused, none duplicated:
 *
 * - `GET /api/career-memory/summary` — header stats (7.15.3)
 * - `GET /api/career-memory?category=` / `POST /api/career-memory` (edit) / `/{id}/confirm` /
 *   `/{id}/forget` — all on the one existing `CareerMemoryController` (7.15.1/7.15.3/7.15.3A)
 * - `GET /api/career-memory/timeline` — Career Timeline (7.15.1)
 * - `GET /api/candidate-profile`, `POST /api/candidate-profile/rebuild` — existing Phase 1 API
 * - `GET /api/interviews` — Interview Intelligence's FIRST REST exposure (service existed, no
 *   controller did — added this phase, no new analysis logic)
 * - `GET /api/diagnostics/career-memory` — existing, aggregate-only, public diagnostics endpoint
 *
 * Recommendation Impact text is generated from what `CareerMemoryBooster` ACTUALLY does (read
 * from its source, not assumed) — see `recommendationImpact()` above for the honesty rationale.
 *
 * Scope NOT built this pass, stated plainly: PDF export (no PDF library in this project — JSON
 * export only), "Recommendation Evolution over time" (no historical recommendation-snapshot
 * infrastructure exists anywhere in this codebase to build it from), and a fabricated
 * skill-by-skill Interview Strengths/Weaknesses matrix (no structured data backs that — the
 * Interview Insights section below shows real interview rounds and real feedback text instead).
 */
export default function AiMemory() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [categoryFilter, setCategoryFilter] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<'newest' | 'oldest' | 'confidence'>('newest');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [editOpen, setEditOpen] = useState(false);

  const summaryQuery = useQuery<CareerMemorySummary>({
    queryKey: ['career-memory', 'summary'],
    queryFn: async () => (await api.get('/api/career-memory/summary')).data,
    retry: false,
  });

  const profileQuery = useQuery<CandidateProfileDto | null>({
    queryKey: ['candidate-profile'],
    queryFn: async () => {
      try {
        return (await api.get('/api/candidate-profile')).data;
      } catch {
        return null; // 404 = disabled or not generated yet — not an error state for this page
      }
    },
    retry: false,
  });

  const memoriesQuery = useQuery<CareerDecisionMemory[]>({
    queryKey: ['career-memory', 'list', categoryFilter],
    queryFn: async () =>
      (await api.get('/api/career-memory', { params: categoryFilter ? { category: categoryFilter } : {} })).data,
    retry: false,
  });

  const timelineQuery = useQuery<CareerDecisionMemory[]>({
    queryKey: ['career-memory', 'timeline'],
    queryFn: async () => (await api.get('/api/career-memory/timeline')).data,
    retry: false,
  });

  const interviewsQuery = useQuery<InterviewDto[]>({
    queryKey: ['interviews'],
    queryFn: async () => {
      try {
        return (await api.get('/api/interviews')).data;
      } catch {
        return [];
      }
    },
    retry: false,
  });

  const diagnosticsQuery = useQuery<CareerMemoryDiagnostics>({
    queryKey: ['diagnostics', 'career-memory'],
    queryFn: async () => (await api.get('/api/diagnostics/career-memory')).data,
    retry: false,
  });

  const confirm = useMutation({
    mutationFn: async (id: string) => (await api.post(`/api/career-memory/${id}/confirm`)).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'Memory verified' });
      qc.invalidateQueries({ queryKey: ['career-memory'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not verify memory' }),
  });

  const forget = useMutation({
    mutationFn: async (id: string) => (await api.post(`/api/career-memory/${id}/forget`)).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'Memory forgotten — the AI will stop using it' });
      qc.invalidateQueries({ queryKey: ['career-memory'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not forget memory' }),
  });

  const rebuild = useMutation({
    mutationFn: async () => (await api.post('/api/candidate-profile/rebuild')).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'AI understanding refreshed' });
      qc.invalidateQueries({ queryKey: ['candidate-profile'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not refresh (feature may be disabled)' }),
  });

  const editMemory = useMutation({
    mutationFn: async (req: EditMemoryRequest) => (await api.post('/api/career-memory', req)).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'Memory saved — verified and prioritized' });
      setEditOpen(false);
      qc.invalidateQueries({ queryKey: ['career-memory'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not save (feature may be disabled)' }),
  });

  const summary = summaryQuery.data;
  const profile = profileQuery.data;
  const memories = memoriesQuery.data ?? [];
  const timeline = timelineQuery.data ?? [];
  const interviews = interviewsQuery.data ?? [];
  const diagnostics = diagnosticsQuery.data;

  const categories = useMemo(() => {
    const set = new Set<string>();
    memories.forEach((m) => set.add(m.category));
    return Array.from(set).sort();
  }, [memories]);

  const visibleMemories = useMemo(() => {
    let list = memories;
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      list = list.filter(
        (m) => m.value?.toLowerCase().includes(q) || m.reason?.toLowerCase().includes(q) || m.category.toLowerCase().includes(q),
      );
    }
    const sorted = [...list];
    if (sort === 'newest') sorted.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    else if (sort === 'oldest') sorted.sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime());
    else sorted.sort((a, b) => (b.confidence ?? 0) - (a.confidence ?? 0));
    return sorted;
  }, [memories, search, sort]);

  /** Point-in-time analytics computed from already-fetched data — no new backend calls, no fabricated trend-over-time (no historical snapshots exist to chart). */
  const analytics = useMemo(() => {
    const byCategory = new Map<string, number>();
    const bySource = new Map<string, number>();
    let verified = 0;
    let ageSumDays = 0;
    for (const m of timeline) {
      byCategory.set(m.category, (byCategory.get(m.category) ?? 0) + 1);
      bySource.set(m.source, (bySource.get(m.source) ?? 0) + 1);
      if (m.userConfirmed) verified++;
      ageSumDays += (Date.now() - new Date(m.createdAt).getTime()) / 86_400_000;
    }
    return {
      byCategory: Array.from(byCategory.entries()).sort((a, b) => b[1] - a[1]),
      bySource: Array.from(bySource.entries()).sort((a, b) => b[1] - a[1]),
      verifiedRatio: timeline.length ? verified / timeline.length : 0,
      avgAgeDays: timeline.length ? ageSumDays / timeline.length : 0,
      mostUsed: [...timeline].sort((a, b) => (b.usageCount ?? 0) - (a.usageCount ?? 0)).slice(0, 5),
    };
  }, [timeline]);

  function toggleExpanded(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function exportProfile() {
    const payload = {
      exportedAt: new Date().toISOString(),
      source: 'CareerPilot AI',
      summary,
      profile,
      memories: timeline,
      interviews,
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `careerpilot-ai-profile-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    toast({ variant: 'success', title: 'AI career profile exported' });
  }

  const nothingLearnedYet =
    !summaryQuery.isLoading && !profileQuery.isLoading && (summary?.totalMemories ?? 0) === 0 && !profile;

  return (
    <div className="space-y-6">
      <PageHeader
        title="AI Learned About You"
        description="My understanding improves every time we interact. Everything below is grounded in your actual resume, conversations, and application activity — nothing here is guessed without evidence."
        actions={
          <>
            <Button size="sm" variant="outline" onClick={() => setEditOpen(true)}>
              <Pencil className="h-4 w-4" />
              Tell the AI something
            </Button>
            <Button size="sm" variant="outline" onClick={exportProfile}>
              <Download className="h-4 w-4" />
              Export (JSON)
            </Button>
            <Button size="sm" variant="outline" onClick={() => rebuild.mutate()} loading={rebuild.isPending}>
              <RefreshCw className="h-4 w-4" />
              Refresh AI understanding
            </Button>
          </>
        }
      />

      <EditMemoryDialog open={editOpen} onOpenChange={setEditOpen} onSubmit={(req) => editMemory.mutate(req)} pending={editMemory.isPending} />

      {nothingLearnedYet ? (
        <EmptyState
          icon={Brain}
          title="Nothing learned yet"
          description="Upload a resume and start using the Copilot / job recommendations — the AI Memory Dashboard fills in automatically as you interact with CareerPilot. This feature may also be disabled for your account."
        />
      ) : (
        <>
          {/* Header stats */}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {summaryQuery.isLoading ? (
              Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-32 w-full" />)
            ) : (
              <>
                <KpiCard label="AI confidence" value={pct(summary?.averageConfidence)} icon={Sparkles} tone="primary" />
                <KpiCard
                  label="Memory entries"
                  value={summary?.totalMemories ?? 0}
                  icon={Brain}
                  tone="info"
                  hint={`${summary?.verifiedCount ?? 0} verified`}
                />
                <KpiCard
                  label="Verified memories"
                  value={summary && summary.totalMemories > 0 ? pct(summary.verifiedCount / summary.totalMemories) : '—'}
                  icon={ShieldCheck}
                  tone="success"
                />
                <KpiCard label="Last updated" value={relativeTime(summary?.lastUpdated)} icon={Clock} tone="warning" />
              </>
            )}
          </div>

          {/* Profile-derived preference cards — reuses GET /api/candidate-profile wholesale */}
          {profile && (
            <div className="grid gap-4 lg:grid-cols-2">
              <Card>
                <CardHeader className="flex-row items-center gap-2">
                  <Sparkles className="h-4 w-4 text-primary" />
                  <CardTitle className="text-base">Career goal</CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">
                  <p className="text-lg font-semibold text-foreground">
                    {profile.currentRole || profile.careerGoals[0] || 'Not yet determined'}
                  </p>
                  <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    {profile.seniority && <Badge tone="neutral">{profile.seniority}</Badge>}
                    {profile.confidenceScore != null && (
                      <Badge tone={confidenceTone(profile.confidenceScore)}>{pct(profile.confidenceScore)} confidence</Badge>
                    )}
                    <span>Source: Resume analysis</span>
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="flex-row items-center gap-2">
                  <Globe2 className="h-4 w-4 text-primary" />
                  <CardTitle className="text-base">Preferred countries</CardTitle>
                </CardHeader>
                <CardContent>
                  {profile.preferredCountries.length === 0 ? (
                    <p className="text-sm text-muted-foreground">No preference captured yet.</p>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {profile.preferredCountries.map((c) => (
                        <Badge key={c} tone="primary">
                          {c}
                        </Badge>
                      ))}
                    </div>
                  )}
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Preferred technologies</CardTitle>
                </CardHeader>
                <CardContent>
                  {profile.technologies.length === 0 ? (
                    <p className="text-sm text-muted-foreground">No technologies captured yet.</p>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {profile.technologies.map((t) => (
                        <Badge key={t} tone="info">
                          {t}
                        </Badge>
                      ))}
                    </div>
                  )}
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Expected salary</CardTitle>
                </CardHeader>
                <CardContent>
                  {profile.salaryTarget != null ? (
                    <>
                      <p className="text-lg font-semibold text-foreground">
                        {profile.salaryTarget.toLocaleString()} {profile.salaryCurrency || ''}
                      </p>
                      <p className="mt-1 text-xs text-muted-foreground">Based on: resume, offer analysis, and preferences you've set</p>
                    </>
                  ) : (
                    <p className="text-sm text-muted-foreground">No salary expectation captured yet.</p>
                  )}
                </CardContent>
              </Card>

              {profile.excludedRoles.length > 0 && (
                <Card className="lg:col-span-2">
                  <CardHeader className="flex-row items-center gap-2">
                    <ThumbsDown className="h-4 w-4 text-danger" />
                    <CardTitle className="text-base">The AI knows you want to avoid</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <div className="flex flex-wrap gap-2">
                      {profile.excludedRoles.map((r) => (
                        <Badge key={r} tone="danger">
                          {r}
                        </Badge>
                      ))}
                    </div>
                  </CardContent>
                </Card>
              )}
            </div>
          )}

          {/* Memory Health */}
          {summary && summary.totalMemories > 0 && (
            <Card>
              <CardHeader className="flex-row items-center gap-2">
                <Wrench className="h-4 w-4 text-muted-foreground" />
                <CardTitle className="text-base">Memory health</CardTitle>
              </CardHeader>
              <CardContent className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                <div>
                  <p className="text-2xl font-semibold tabular-nums text-foreground">{pct(summary.averageConfidence)}</p>
                  <p className="text-xs text-muted-foreground">Overall confidence</p>
                </div>
                <div>
                  <p className="text-2xl font-semibold tabular-nums text-success">{summary.verifiedCount}</p>
                  <p className="text-xs text-muted-foreground">Verified</p>
                </div>
                <div>
                  <p className="text-2xl font-semibold tabular-nums text-warning">{summary.needsReviewCount}</p>
                  <p className="text-xs text-muted-foreground">Needs review</p>
                </div>
                <div>
                  <p className="text-2xl font-semibold tabular-nums text-danger">{summary.conflictingCount}</p>
                  <p className="text-xs text-muted-foreground">Conflicting</p>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Memory cards — search, filter, sort, expandable explainability + recommendation impact */}
          <Card>
            <CardHeader className="flex-col items-stretch gap-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <CardTitle className="text-base">Remembered decisions</CardTitle>
                <div className="flex items-center gap-2">
                  <div className="relative">
                    <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
                    <Input
                      placeholder="Search memories…"
                      className="h-8 w-48 pl-8 text-xs"
                      value={search}
                      onChange={(e) => setSearch(e.target.value)}
                    />
                  </div>
                  <select
                    className="h-8 rounded-lg border border-input bg-card px-2 text-xs text-foreground"
                    value={sort}
                    onChange={(e) => setSort(e.target.value as typeof sort)}
                  >
                    <option value="newest">Newest first</option>
                    <option value="oldest">Oldest first</option>
                    <option value="confidence">Highest confidence</option>
                  </select>
                </div>
              </div>
              <div className="flex flex-wrap gap-1.5">
                <Button size="sm" variant={categoryFilter === null ? 'secondary' : 'ghost'} onClick={() => setCategoryFilter(null)}>
                  All
                </Button>
                {categories.map((c) => (
                  <Button key={c} size="sm" variant={categoryFilter === c ? 'secondary' : 'ghost'} onClick={() => setCategoryFilter(c)}>
                    {categoryLabel(c)}
                  </Button>
                ))}
              </div>
            </CardHeader>
            <CardContent>
              {memoriesQuery.isLoading ? (
                <div className="grid gap-3 sm:grid-cols-2">
                  {Array.from({ length: 4 }).map((_, i) => (
                    <Skeleton key={i} className="h-32 w-full" />
                  ))}
                </div>
              ) : visibleMemories.length === 0 ? (
                <EmptyState compact icon={Brain} title="No memories match" />
              ) : (
                <div className="grid gap-3 sm:grid-cols-2">
                  {visibleMemories.map((m) => {
                    const isOpen = expanded.has(m.id);
                    const impact = recommendationImpact(m);
                    return (
                      <div key={m.id} className="rounded-xl border border-border bg-card p-4">
                        <div className="flex items-start justify-between gap-2">
                          <div>
                            <p className="text-sm font-semibold text-foreground">{m.value || m.decisionType}</p>
                            <p className="text-xs text-muted-foreground">{categoryLabel(m.category)}</p>
                          </div>
                          <Badge tone={confidenceTone(m.confidence)}>{pct(m.confidence)}</Badge>
                        </div>
                        {m.reason && <p className="mt-2 text-xs text-muted-foreground">"{m.reason}"</p>}
                        <div className="mt-3 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                          <Badge tone="neutral">{m.source.replace(/_/g, ' ').toLowerCase()}</Badge>
                          {m.userConfirmed && (
                            <Badge tone="success">
                              <ShieldCheck className="h-3 w-3" /> Verified
                            </Badge>
                          )}
                          <span>{relativeTime(m.createdAt)}</span>
                        </div>

                        <button
                          type="button"
                          onClick={() => toggleExpanded(m.id)}
                          className="mt-3 flex items-center gap-1 text-xs font-medium text-primary hover:underline"
                        >
                          {isOpen ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
                          {isOpen ? 'Hide details' : 'Why did the AI learn this?'}
                        </button>

                        {isOpen && (
                          <div className="mt-3 space-y-3 rounded-lg bg-muted/40 p-3 text-xs">
                            <div className="grid grid-cols-2 gap-2 text-muted-foreground">
                              <div>
                                <span className="font-medium text-foreground">Source: </span>
                                {m.source.replace(/_/g, ' ').toLowerCase()}
                              </div>
                              <div>
                                <span className="font-medium text-foreground">Decision type: </span>
                                {m.decisionType}
                              </div>
                              <div>
                                <span className="font-medium text-foreground">Created: </span>
                                {new Date(m.createdAt).toLocaleString()}
                              </div>
                              <div>
                                <span className="font-medium text-foreground">Times used: </span>
                                {m.usageCount ?? 0}
                                {m.lastUsedAt ? ` (last ${relativeTime(m.lastUsedAt)})` : ''}
                              </div>
                              {m.workflowId && (
                                <div className="col-span-2 truncate">
                                  <span className="font-medium text-foreground">Workflow: </span>
                                  {m.workflowId}
                                </div>
                              )}
                              {m.correlationId && (
                                <div className="col-span-2 truncate">
                                  <span className="font-medium text-foreground">Correlation ID: </span>
                                  {m.correlationId}
                                </div>
                              )}
                            </div>
                            <div>
                              <p className={`font-medium ${impact.real ? 'text-foreground' : 'text-muted-foreground'}`}>
                                Recommendation impact{!impact.real && ' (informational only)'}
                              </p>
                              <ul className="mt-1 list-inside list-disc space-y-0.5 text-muted-foreground">
                                {impact.lines.map((line, i) => (
                                  <li key={i}>{line}</li>
                                ))}
                              </ul>
                            </div>
                          </div>
                        )}

                        <div className="mt-3 flex gap-2">
                          {!m.userConfirmed && (
                            <Button size="sm" variant="outline" onClick={() => confirm.mutate(m.id)} loading={confirm.isPending}>
                              <Check className="h-3.5 w-3.5" /> Approve
                            </Button>
                          )}
                          <Button size="sm" variant="ghost" onClick={() => forget.mutate(m.id)} loading={forget.isPending}>
                            Forget
                          </Button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Career Timeline — reuses the existing GET /api/career-memory/timeline endpoint */}
          <Card>
            <CardHeader className="flex-row items-center gap-2">
              <Clock className="h-4 w-4 text-muted-foreground" />
              <CardTitle className="text-base">Career timeline</CardTitle>
            </CardHeader>
            <CardContent>
              {timelineQuery.isLoading ? (
                <Skeleton className="h-48 w-full" />
              ) : timeline.length === 0 ? (
                <p className="text-sm text-muted-foreground">No career events recorded yet.</p>
              ) : (
                <ol className="space-y-4 border-l border-border pl-4">
                  {timeline.slice(0, 20).map((m) => (
                    <li key={m.id} className="relative">
                      <span className="absolute -left-[21px] top-1 h-2.5 w-2.5 rounded-full bg-primary" />
                      <p className="text-sm font-medium text-foreground">
                        {m.decisionType.replace(/_/g, ' ').toLowerCase()}
                        {m.value ? `: ${m.value}` : ''}
                      </p>
                      {m.reason && <p className="text-xs text-muted-foreground">{m.reason}</p>}
                      <p className="text-xs text-muted-foreground/80">{relativeTime(m.createdAt)}</p>
                    </li>
                  ))}
                </ol>
              )}
            </CardContent>
          </Card>

          {/* Interview Insights — real rounds + real feedback text, reusing InterviewService's data via its first REST controller. NOT a fabricated skill matrix. */}
          <Card>
            <CardHeader className="flex-row items-center gap-2">
              <Mic className="h-4 w-4 text-muted-foreground" />
              <CardTitle className="text-base">Interview insights</CardTitle>
            </CardHeader>
            <CardContent>
              {interviewsQuery.isLoading ? (
                <Skeleton className="h-32 w-full" />
              ) : interviews.length === 0 ? (
                <p className="text-sm text-muted-foreground">No interview rounds recorded yet.</p>
              ) : (
                <div className="space-y-3">
                  {interviews.slice(0, 10).map((iv) => (
                    <div key={iv.id} className="rounded-lg border border-border p-3">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <p className="text-sm font-medium text-foreground">{categoryLabel(iv.interviewType)}</p>
                        <Badge
                          tone={iv.result === 'PASSED' ? 'success' : iv.result === 'FAILED' ? 'danger' : 'neutral'}
                        >
                          {iv.result ?? 'SCHEDULED'}
                        </Badge>
                      </div>
                      <p className="text-xs text-muted-foreground">{relativeTime(iv.scheduledAt ?? iv.createdAt)}</p>
                      {iv.feedback.map((f, i) => (
                        <p key={i} className="mt-1 text-xs text-muted-foreground">
                          {f.rating != null && <span className="font-medium text-foreground">{f.rating}/5 — </span>}
                          {f.feedback}
                        </p>
                      ))}
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Memory Analytics — point-in-time only, computed client-side from already-fetched data. No trend-over-time (no historical snapshots exist). */}
          <Card>
            <CardHeader className="flex-row items-center gap-2">
              <BarChart3 className="h-4 w-4 text-muted-foreground" />
              <CardTitle className="text-base">Memory analytics</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                <div>
                  <p className="text-xl font-semibold tabular-nums text-foreground">{Math.round(analytics.avgAgeDays)}d</p>
                  <p className="text-xs text-muted-foreground">Average memory age</p>
                </div>
                <div>
                  <p className="text-xl font-semibold tabular-nums text-foreground">{pct(analytics.verifiedRatio)}</p>
                  <p className="text-xs text-muted-foreground">Verification ratio</p>
                </div>
                <div>
                  <p className="text-xl font-semibold tabular-nums text-foreground">{timeline.length}</p>
                  <p className="text-xs text-muted-foreground">Total (all-time)</p>
                </div>
                <div>
                  <p className="text-xl font-semibold tabular-nums text-foreground">{categories.length}</p>
                  <p className="text-xs text-muted-foreground">Active categories</p>
                </div>
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <p className="mb-2 text-xs font-medium text-muted-foreground">By category</p>
                  <div className="space-y-1.5">
                    {analytics.byCategory.slice(0, 8).map(([cat, count]) => (
                      <div key={cat} className="flex items-center justify-between text-xs">
                        <span className="text-muted-foreground">{categoryLabel(cat)}</span>
                        <span className="tabular-nums font-medium text-foreground">{count}</span>
                      </div>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="mb-2 text-xs font-medium text-muted-foreground">Most-used memories</p>
                  <div className="space-y-1.5">
                    {analytics.mostUsed.map((m) => (
                      <div key={m.id} className="flex items-center justify-between text-xs">
                        <span className="truncate text-muted-foreground">{m.value || m.decisionType}</span>
                        <span className="tabular-nums font-medium text-foreground">{m.usageCount ?? 0}×</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Admin/system diagnostics — reuses the existing public GET /api/diagnostics/career-memory verbatim, only visualized here. */}
          {diagnostics && (
            <Card>
              <CardHeader className="flex-row items-center gap-2">
                <Activity className="h-4 w-4 text-muted-foreground" />
                <CardTitle className="text-base">System diagnostics</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={diagnostics.health === 'UP' ? 'success' : diagnostics.health === 'DEGRADED' ? 'warning' : 'neutral'}>
                    {diagnostics.health}
                  </Badge>
                  <span className="text-xs text-muted-foreground">
                    Extraction: {diagnostics.extractionSuccesses ?? 0}/{diagnostics.extractionAttempts ?? 0} succeeded ·
                    Retrieval avg {diagnostics.avgRetrievalLatencyMs?.toFixed(1) ?? '—'}ms
                  </span>
                </div>
                {diagnostics.conversationIntelligence?.enabled && (
                  <p className="text-xs text-muted-foreground">
                    Conversation Intelligence: {diagnostics.conversationIntelligence.conversationsAnalyzed ?? 0} analyzed,{' '}
                    {diagnostics.conversationIntelligence.memoriesAccepted ?? 0} accepted,{' '}
                    {diagnostics.conversationIntelligence.rejectedLowConfidence ?? 0} below confidence threshold,{' '}
                    {diagnostics.conversationIntelligence.duplicatesSkipped ?? 0} duplicates skipped.
                  </p>
                )}
                {diagnostics.topRemembered && diagnostics.topRemembered.length > 0 && (
                  <div>
                    <p className="mb-1 text-xs font-medium text-muted-foreground">Top remembered (all users)</p>
                    <div className="flex flex-wrap gap-1.5">
                      {diagnostics.topRemembered.slice(0, 8).map((r, i) => (
                        <Badge key={i} tone="neutral">
                          {r.value} ({r.uses})
                        </Badge>
                      ))}
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
