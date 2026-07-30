import { useMemo, useState, memo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Activity,
  Award,
  Briefcase,
  Brain,
  CalendarClock,
  CheckCircle2,
  Circle,
  Clock,
  Compass,
  Flag,
  Globe2,
  GraduationCap,
  Handshake,
  ListChecks,
  Mail,
  MapPin,
  Mic,
  Pause,
  Pencil,
  RefreshCw,
  Rocket,
  Sparkles,
  Target,
  TrendingUp,
  Trophy,
  Users,
  Wallet,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { cn } from '@/lib/cn';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { PageHeader } from '@/components/common/PageHeader';
import { Dialog, DialogHeader, DialogTitle, DialogDescription, DialogBody, DialogFooter } from '@/components/ui/dialog';
import { Input, Textarea, Label } from '@/components/ui/input';
import { Tooltip } from '@/components/ui/tooltip';
import { useToast } from '@/components/ui/toast';
import { useWorkflowStatus } from '@/hooks/useWorkflowStatus';
import { useObservability, healthTone } from '@/hooks/useObservability';
import { runStatusTone, runStatusLabel, currentStage } from '@/lib/workflowStatus';
import type { WorkflowRun, WorkflowAgent, AgentStatus, CareerDecisionMemory } from '@/types/workflow';

// ---------------------------------------------------------------------------
// Wire types — copied verbatim from the real backend DTOs. See the "Real vs.
// omitted" note at the bottom of this file for exactly which requested fields
// have no backend source and were therefore left out rather than invented.
// ---------------------------------------------------------------------------

interface MissionResponse {
  id: string;
  userId: string;
  missionStatement: string;
  targetRole: string;
  targetLevel: string | null;
  targetIndustries: string[];
  targetCountries: string[];
  salaryExpectationMin: string | null;
  salaryExpectationMax: string | null;
  salaryCurrency: string | null;
  timelineMonths: number | null;
  careerDirection: string | null;
  skillsToAcquire: string[];
  currentSkills: string[];
  careerAmbition: string | null;
  status: 'ACTIVE' | 'PAUSED' | 'COMPLETED';
  createdAt: string;
  updatedAt: string;
}

interface StrategyActionResponse {
  id: string;
  missionId: string;
  title: string;
  description: string | null;
  targetDate: string | null;
  status: string;
  createdAt: string;
}

interface StrategyPlanResponse {
  id: string;
  missionId: string;
  timeframeDays: number | null;
  status: string;
  generatedAt: string;
  actions: StrategyActionResponse[];
}

interface WorkflowDecisionResponse {
  workflowId: string;
  reason: string;
}

interface MissionExecutionResponse {
  id: string;
  missionId: string;
  decisionSummary: string | null;
  resumeScoreAtRun: number | null;
  ranAt: string;
  decisions: WorkflowDecisionResponse[];
}

interface CountryProfileResponse {
  countryCode: string;
  countryName: string;
  tier: string;
  visaProbabilityScore: number | null;
  jobStabilityScore: number | null;
}

interface CareerFitResponse {
  countryCode: string;
  displayName: string;
  fitScore: number;
  explanation: string;
}

interface DailyCareerSummaryDto {
  id: string;
  missionProgressPercent: number | null;
  currentStrategyCountry: string | null;
  alternativeStrategyCountry: string | null;
  todaysMissionTasksJson: string | null;
  highRiskAreasJson: string | null;
  estimatedCompletionTimeline: string | null;
  missionRecommendation: string | null;
  createdAt: string;
}

interface WorkflowRunLite {
  threadId: string;
  status: string;
  atsScore?: number | null;
  resumeScore?: number | null;
  jobMatchScore?: number | null;
  createdAt?: string;
}

interface DashboardSnapshot {
  resumeScore: number;
  atsScore: number;
  jobMatchScore: number;
  interviewReadinessScore: number;
  offerProbabilityScore: number;
  applications: Record<string, number>;
  recentRuns: WorkflowRunLite[];
}

interface CareerIntelligenceRow {
  dimension?: string | null;
  dimensionKey?: string | null;
  probability?: number | null;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function parseJsonSafe<T>(raw?: string | null): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function itemsToText(raw?: string | null): string[] {
  const parsed = parseJsonSafe<unknown[]>(raw);
  if (!Array.isArray(parsed)) return [];
  return parsed.map((item) => {
    if (typeof item === 'string') return item;
    if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>;
      const text = o.description ?? o.title ?? o.task ?? o.name ?? o.risk;
      if (typeof text === 'string') return text;
    }
    return JSON.stringify(item);
  });
}

function is404(err: unknown): boolean {
  return !!(err && typeof err === 'object' && 'response' in err && (err as any).response?.status === 404);
}

function formatSalary(min: string | null, max: string | null, currency: string | null): string | null {
  if (!min && !max) return null;
  const cur = currency || '';
  const fmt = (v: string) => `${cur}${Math.round(Number(v)).toLocaleString()}`;
  if (min && max) return `${fmt(min)} – ${fmt(max)}`;
  return fmt((min || max)!);
}

const COUNTRY_FLAGS: Record<string, string> = {
  germany: '🇩🇪', netherlands: '🇳🇱', canada: '🇨🇦', australia: '🇦🇺',
  luxembourg: '🇱🇺', ireland: '🇮🇪', usa: '🇺🇸', 'united states': '🇺🇸',
  uk: '🇬🇧', 'united kingdom': '🇬🇧', india: '🇮🇳', singapore: '🇸🇬',
  sweden: '🇸🇪', switzerland: '🇨🇭', france: '🇫🇷', spain: '🇪🇸',
};
function countryFlag(name?: string): string {
  return COUNTRY_FLAGS[(name ?? '').toLowerCase()] ?? '🌍';
}

/**
 * "On track / At risk / Ahead" — a presentation-layer comparison of two real
 * numbers (elapsed time vs. actual progress), not a new backend calculation.
 * Only rendered when both a timeline and a progress figure genuinely exist.
 */
function missionStatus(progressPct: number | null, createdAt: string, timelineMonths: number | null): { label: string; tone: BadgeTone } | null {
  if (progressPct == null || !timelineMonths) return null;
  const elapsedMonths = (Date.now() - new Date(createdAt).getTime()) / (1000 * 60 * 60 * 24 * 30.44);
  const expectedPct = Math.min(100, Math.max(0, (elapsedMonths / timelineMonths) * 100));
  const delta = progressPct - expectedPct;
  if (delta >= 10) return { label: 'Ahead of schedule', tone: 'success' };
  if (delta <= -15) return { label: 'At risk', tone: 'danger' };
  return { label: 'On track', tone: 'primary' };
}

function scoreTone(v: number): 'success' | 'warning' | 'danger' {
  if (v >= 75) return 'success';
  if (v >= 45) return 'warning';
  return 'danger';
}

/** Static class strings only — Tailwind's JIT compiler can't see interpolated `bg-${x}/10`. */
const SCORE_TONE_CLASSES: Record<'success' | 'warning' | 'danger', string> = {
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/10 text-warning',
  danger: 'bg-danger/10 text-danger',
};

/**
 * The real, fixed 8-node LangGraph pipeline (see CLAUDE.md / `Workflow.tsx`'s own `PIPELINE`
 * constant — these are the exact `WorkflowAgent.name` strings the backend already sends).
 * Persona labels are decoration on top of real stage names, not new data — Visa/Relocation
 * personas from the original mockup are intentionally absent because no such agent exists.
 */
const AGENT_PERSONAS: Record<string, { emoji: string; persona: string; icon: typeof Sparkles }> = {
  'Resume Intelligence': { emoji: '📄', persona: 'Resume Expert', icon: Briefcase },
  'Job Discovery': { emoji: '🌍', persona: 'Global Job Hunter', icon: Globe2 },
  'ATS Optimization': { emoji: '🎯', persona: 'ATS Optimizer', icon: Target },
  'Interview Prep': { emoji: '🎤', persona: 'Interview Coach', icon: Mic },
  'Career Strategy': { emoji: '🧭', persona: 'Career Strategist', icon: Compass },
  'Salary Intelligence': { emoji: '💰', persona: 'Salary Advisor', icon: Wallet },
  'Human Approval': { emoji: '🤝', persona: 'Your Approval', icon: Handshake },
  'Application Tracking': { emoji: '📬', persona: 'Application Tracker', icon: Mail },
};

const AGENT_STATUS_TONE: Record<AgentStatus, BadgeTone> = {
  COMPLETED: 'success',
  ACTIVE: 'primary',
  WAITING_FOR_APPROVAL: 'warning',
  PENDING: 'neutral',
  FAILED: 'danger',
  REJECTED: 'danger',
};

const AGENT_STATUS_LABEL: Record<AgentStatus, string> = {
  COMPLETED: 'Completed',
  ACTIVE: 'Running',
  WAITING_FOR_APPROVAL: 'Approval required',
  PENDING: 'Waiting',
  FAILED: 'Blocked',
  REJECTED: 'Blocked',
};

function friendlyWorkflowName(workflowId: string): string {
  return workflowId
    .replace(/_V\d+$/i, '')
    .split('_')
    .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
    .join(' ');
}

// ---------------------------------------------------------------------------
// Page — mission resolution + create flow
// ---------------------------------------------------------------------------

export default function MissionDashboard() {
  const [createOpen, setCreateOpen] = useState(false);

  const missionsQuery = useQuery<MissionResponse[]>({
    queryKey: ['mission', 'list'],
    queryFn: async () => (await api.get('/api/missions')).data,
  });

  if (missionsQuery.isLoading) return <MissionControlSkeleton />;

  if (missionsQuery.isError) {
    return (
      <div className="space-y-6">
        <PageHeader title="Career Mission" description="Your personal AI Career Operating System." />
        <EmptyState icon={Compass} title="Couldn't load your mission" description="Something went wrong reaching the server. Try refreshing the page." />
      </div>
    );
  }

  const missions = missionsQuery.data ?? [];
  const activeMission = missions.find((m) => m.status === 'ACTIVE') ?? missions[0] ?? null;

  if (!activeMission) {
    return (
      <div className="space-y-6">
        <PageHeader title="Career Mission" description="Your personal AI Career Operating System." />
        <EmptyState
          icon={Rocket}
          title="Define your career mission"
          description="Tell CareerPilot what you're working toward — a target role, country, and timeline — and this page becomes your daily command centre for getting there."
          action={
            <Button onClick={() => setCreateOpen(true)}>
              <Rocket className="h-4 w-4" /> Create your mission
            </Button>
          }
        />
        <MissionFormDialog open={createOpen} onOpenChange={setCreateOpen} mode="create" />
      </div>
    );
  }

  return <MissionControlDashboard mission={activeMission} />;
}

// ---------------------------------------------------------------------------
// Create / Edit mission dialog (shared)
// ---------------------------------------------------------------------------

function MissionFormDialog({
  open,
  onOpenChange,
  mode,
  mission,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  mode: 'create' | 'edit';
  mission?: MissionResponse;
}) {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [form, setForm] = useState({
    missionStatement: mission?.missionStatement ?? '',
    targetRole: mission?.targetRole ?? '',
    targetLevel: mission?.targetLevel ?? '',
    targetCountries: mission?.targetCountries?.join(', ') ?? '',
    timelineMonths: mission?.timelineMonths ? String(mission.timelineMonths) : '',
  });

  const save = useMutation({
    mutationFn: async () => {
      const payload = {
        ...mission,
        missionStatement: form.missionStatement,
        targetRole: form.targetRole,
        targetLevel: form.targetLevel || null,
        targetCountries: form.targetCountries ? form.targetCountries.split(',').map((s) => s.trim()).filter(Boolean) : [],
        timelineMonths: form.timelineMonths ? Number(form.timelineMonths) : null,
        status: mission?.status ?? 'ACTIVE',
      };
      if (mode === 'edit' && mission) {
        return (await api.put(`/api/missions/${mission.id}`, payload)).data as MissionResponse;
      }
      return (await api.post('/api/missions', payload)).data as MissionResponse;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['mission'] });
      toast({ title: mode === 'edit' ? 'Mission updated' : 'Mission created' });
      onOpenChange(false);
    },
    onError: () => toast({ title: 'Could not save your mission', variant: 'error' }),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange} size="lg">
      <DialogHeader onClose={() => onOpenChange(false)}>
        <DialogTitle>{mode === 'edit' ? 'Edit your mission' : 'Create your career mission'}</DialogTitle>
        <DialogDescription>This is the north star every other page builds toward.</DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-4">
        <div>
          <Label>Mission statement</Label>
          <Textarea
            placeholder="Become a Principal Engineer in Germany within 18 months"
            value={form.missionStatement}
            onChange={(e) => setForm((f) => ({ ...f, missionStatement: e.target.value }))}
          />
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <Label>Target role</Label>
            <Input placeholder="Principal Engineer" value={form.targetRole} onChange={(e) => setForm((f) => ({ ...f, targetRole: e.target.value }))} />
          </div>
          <div>
            <Label>Target level</Label>
            <Input placeholder="Principal" value={form.targetLevel} onChange={(e) => setForm((f) => ({ ...f, targetLevel: e.target.value }))} />
          </div>
          <div>
            <Label>Target countries</Label>
            <Input placeholder="Germany, Netherlands" value={form.targetCountries} onChange={(e) => setForm((f) => ({ ...f, targetCountries: e.target.value }))} />
          </div>
          <div>
            <Label>Timeline (months)</Label>
            <Input type="number" placeholder="18" value={form.timelineMonths} onChange={(e) => setForm((f) => ({ ...f, timelineMonths: e.target.value }))} />
          </div>
        </div>
      </DialogBody>
      <DialogFooter>
        <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
        <Button loading={save.isPending} disabled={!form.missionStatement || !form.targetRole} onClick={() => save.mutate()}>
          {mode === 'edit' ? 'Save changes' : 'Create mission'}
        </Button>
      </DialogFooter>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// Mission Control Dashboard — the five zones
// ---------------------------------------------------------------------------

function MissionControlDashboard({ mission }: { mission: MissionResponse }) {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [editOpen, setEditOpen] = useState(false);

  const strategyQuery = useQuery<StrategyPlanResponse | null>({
    queryKey: ['mission', mission.id, 'strategy'],
    queryFn: async () => {
      try { return (await api.get(`/api/strategy/${mission.id}`)).data; }
      catch (err) { if (is404(err)) return null; throw err; }
    },
    retry: false,
  });

  const orchestratorQuery = useQuery<MissionExecutionResponse | null>({
    queryKey: ['mission', mission.id, 'orchestrator'],
    queryFn: async () => {
      try { return (await api.get(`/api/orchestrator/status/${mission.id}`)).data; }
      catch (err) { if (is404(err)) return null; throw err; }
    },
    retry: false,
  });

  const dailySummaryQuery = useQuery<DailyCareerSummaryDto | null>({
    queryKey: ['mission', 'daily-summary'],
    queryFn: async () => {
      try { return (await api.get('/api/daily-discovery/summary')).data; }
      catch (err) { if (is404(err)) return null; throw err; }
    },
    retry: false,
  });

  const dashboardQuery = useQuery<DashboardSnapshot>({
    queryKey: ['dashboard'],
    queryFn: async () => (await api.get('/api/dashboard')).data,
    retry: false,
  });

  const countriesQuery = useQuery<CountryProfileResponse[]>({
    queryKey: ['mission', 'countries'],
    queryFn: async () => { try { return (await api.get('/api/countries')).data; } catch { return []; } },
    retry: false,
  });

  const countryFitQuery = useQuery<CareerFitResponse[]>({
    queryKey: ['mission', mission.id, 'country-fit', countriesQuery.data?.map((c) => c.countryCode).join(',')],
    enabled: (countriesQuery.data?.length ?? 0) > 0,
    queryFn: async () => {
      const countries = countriesQuery.data ?? [];
      const results = await Promise.all(
        countries.map(async (c) => {
          try { return (await api.get(`/api/countries/${c.countryCode}/career-fit`, { params: { missionId: mission.id } })).data as CareerFitResponse; }
          catch { return null; }
        }),
      );
      return results.filter((r): r is CareerFitResponse => !!r).sort((a, b) => b.fitScore - a.fitScore);
    },
    retry: false,
  });

  const careerIntelligenceQuery = useQuery<CareerIntelligenceRow[]>({
    queryKey: ['mission', 'career-intelligence'],
    queryFn: async () => { try { return (await api.get('/api/workflow/career-intelligence')).data; } catch { return []; } },
    retry: false,
  });

  const workflowsQuery = useQuery<WorkflowRun[]>({
    queryKey: ['mission', 'workflows'],
    queryFn: async () => { try { return (await api.get('/api/workflows')).data; } catch { return []; } },
    retry: false,
  });

  const careerMemoryQuery = useQuery<CareerDecisionMemory[]>({
    queryKey: ['mission', 'career-memory'],
    queryFn: async () => { try { return (await api.get('/api/career-memory')).data; } catch { return []; } },
    retry: false,
  });

  const observabilityQuery = useObservability<{ overall?: string }>(30_000);

  const runOrchestrator = useMutation({
    mutationFn: async () => (await api.post(`/api/orchestrator/run/${mission.id}`)).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['mission', mission.id, 'orchestrator'] }); toast({ title: 'Recommendation refreshed' }); },
    onError: () => toast({ title: 'Could not refresh the recommendation', variant: 'error' }),
  });

  const generateStrategy = useMutation({
    mutationFn: async () => (await api.post('/api/strategy/generate', { missionId: mission.id })).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['mission', mission.id, 'strategy'] }); toast({ title: 'Plan generated' }); },
    onError: () => toast({ title: 'Could not generate a plan', variant: 'error' }),
  });

  const completeAction = useMutation({
    mutationFn: async (actionId: string) => (await api.put(`/api/strategy/action/${actionId}/complete`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['mission', mission.id, 'strategy'] }),
    onError: () => toast({ title: 'Could not update that task', variant: 'error' }),
  });

  const pauseMission = useMutation({
    mutationFn: async () => (await api.put(`/api/missions/${mission.id}`, { ...mission, status: mission.status === 'PAUSED' ? 'ACTIVE' : 'PAUSED' })).data,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['mission'] }); toast({ title: mission.status === 'PAUSED' ? 'Mission resumed' : 'Mission paused' }); },
    onError: () => toast({ title: 'Could not update mission status', variant: 'error' }),
  });

  const strategy = strategyQuery.data;
  const orchestration = orchestratorQuery.data;
  const dailySummary = dailySummaryQuery.data;
  const dashboard = dashboardQuery.data;
  const actions = strategy?.actions ?? [];
  const completedActions = actions.filter((a) => a.status === 'COMPLETED');
  const planCompletionPct = actions.length > 0 ? Math.round((completedActions.length / actions.length) * 100) : null;
  const progressPct = dailySummary?.missionProgressPercent ?? planCompletionPct;
  const progressSource = dailySummary?.missionProgressPercent != null ? 'AI-computed' : 'Based on completed plan actions';
  const eta = dailySummary?.estimatedCompletionTimeline ?? (mission.timelineMonths ? `${mission.timelineMonths} months` : null);
  const status = missionStatus(progressPct, mission.createdAt, mission.timelineMonths);
  const confidenceRow = (careerIntelligenceQuery.data ?? [])
    .filter((r) => typeof r.probability === 'number')
    .sort((a, b) => (b.probability ?? 0) - (a.probability ?? 0))[0];
  const confidencePct = confidenceRow ? Math.round((confidenceRow.probability ?? 0) * 100) : null;
  const recommendationText = dailySummary?.missionRecommendation ?? orchestration?.decisionSummary ?? null;
  const salary = formatSalary(mission.salaryExpectationMin, mission.salaryExpectationMax, mission.salaryCurrency);

  const activity = useMemo(() => {
    const events: { label: string; at: string; icon: typeof Flag; tone: BadgeTone }[] = [
      { label: 'Mission created', at: mission.createdAt, icon: Flag, tone: 'primary' },
    ];
    if (strategy?.generatedAt) events.push({ label: 'Strategy plan generated', at: strategy.generatedAt, icon: ListChecks, tone: 'info' });
    if (orchestration?.ranAt) events.push({ label: 'AI recommendation generated', at: orchestration.ranAt, icon: Compass, tone: 'primary' });
    if (dailySummary?.createdAt) events.push({ label: 'Daily brief generated', at: dailySummary.createdAt, icon: Sparkles, tone: 'success' });
    completedActions.forEach((a) => events.push({ label: `Completed: ${a.title}`, at: a.createdAt, icon: CheckCircle2, tone: 'success' }));
    return events.sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime());
  }, [mission.createdAt, strategy?.generatedAt, orchestration?.ranAt, dailySummary?.createdAt, completedActions]);

  const latestRun = workflowsQuery.data?.[0];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Career Mission"
        description="Your personal AI Career Operating System."
        actions={<OperationsIndicator overall={observabilityQuery.data?.overall} />}
      />

      <Zone1Hero
        mission={mission}
        salary={salary}
        progressPct={progressPct}
        progressSource={progressSource}
        eta={eta}
        status={status}
        confidencePct={confidencePct}
        recommendationText={recommendationText}
        currentStrategy={dailySummary?.currentStrategyCountry}
        alternativeStrategy={dailySummary?.alternativeStrategyCountry}
        onEdit={() => setEditOpen(true)}
        onPause={() => pauseMission.mutate()}
        pausePending={pauseMission.isPending}
      />

      <div className="grid gap-4 lg:grid-cols-2">
        <Zone2TodaysMission
          dailyTasks={itemsToText(dailySummary?.todaysMissionTasksJson)}
          dailyLoading={dailySummaryQuery.isLoading}
          actions={actions}
          actionsLoading={strategyQuery.isLoading}
          hasStrategy={!!strategy}
          onGenerate={() => generateStrategy.mutate()}
          generatePending={generateStrategy.isPending}
          onComplete={(id) => completeAction.mutate(id)}
          completePending={completeAction.isPending}
        />
        <Zone4AiCoach
          recommendationText={recommendationText}
          decisions={orchestration?.decisions ?? []}
          generatedAt={dailySummary?.missionRecommendation ? dailySummary.createdAt : orchestration?.ranAt}
          isLoading={orchestratorQuery.isLoading}
          onRefresh={() => runOrchestrator.mutate()}
          refreshPending={runOrchestrator.isPending}
        />
      </div>

      <AiActivitySection latestRun={latestRun} />

      <WorkflowQueueCard runs={workflowsQuery.data ?? []} isLoading={workflowsQuery.isLoading} />

      <Zone3DreamJourney events={activity} />

      <Zone5MissionHealth dashboard={dashboard} isLoading={dashboardQuery.isLoading} />

      {(countryFitQuery.data?.length ?? 0) > 0 && (
        <CountryStrategyCard
          countries={countryFitQuery.data!}
          profiles={countriesQuery.data ?? []}
          currentStrategy={dailySummary?.currentStrategyCountry}
          alternativeStrategy={dailySummary?.alternativeStrategyCountry}
        />
      )}

      <CareerMemoryCard memories={careerMemoryQuery.data ?? []} isLoading={careerMemoryQuery.isLoading} />

      <MissionFormDialog open={editOpen} onOpenChange={setEditOpen} mode="edit" mission={mission} />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Zone 1 — Mission Hero
// ---------------------------------------------------------------------------

const Zone1Hero = memo(function Zone1Hero({
  mission, salary, progressPct, progressSource, eta, status, confidencePct, recommendationText,
  currentStrategy, alternativeStrategy, onEdit, onPause, pausePending,
}: {
  mission: MissionResponse;
  salary: string | null;
  progressPct: number | null;
  progressSource: string;
  eta: string | null;
  status: { label: string; tone: BadgeTone } | null;
  confidencePct: number | null;
  recommendationText: string | null;
  currentStrategy?: string | null;
  alternativeStrategy?: string | null;
  onEdit: () => void;
  onPause: () => void;
  pausePending: boolean;
}) {
  const primaryCountry = mission.targetCountries[0];
  return (
    <Card className="relative overflow-hidden border-0 bg-gradient-to-br from-primary via-primary to-secondary text-primary-foreground shadow-lg">
      <div className="pointer-events-none absolute -right-16 -top-20 h-64 w-64 rounded-full bg-white/10 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-24 right-40 h-56 w-56 rounded-full bg-white/10 blur-3xl" />
      <div className="relative flex flex-col gap-8 p-6 md:p-10">
        <div className="flex flex-col justify-between gap-6 md:flex-row md:items-start">
          <div className="max-w-xl">
            <div className="flex flex-wrap items-center gap-2">
              <Badge className="bg-white/15 text-white ring-white/20"><Rocket className="h-3 w-3" /> Career Mission</Badge>
              {status && <Badge className="bg-white/15 text-white ring-white/20">{status.label}</Badge>}
              {mission.status === 'PAUSED' && <Badge className="bg-white/15 text-white ring-white/20">Paused</Badge>}
            </div>
            <h1 className="mt-4 text-3xl font-semibold leading-tight tracking-tight md:text-4xl">
              {mission.targetRole}
            </h1>
            <p className="mt-2 max-w-lg text-sm text-white/85">{mission.missionStatement}</p>

            <div className="mt-6 flex flex-wrap gap-x-8 gap-y-4 text-sm">
              {primaryCountry && (
                <HeroFact label="Target country" value={`${countryFlag(primaryCountry)} ${primaryCountry}`} />
              )}
              {salary && <HeroFact label="Salary goal" value={salary} />}
              {eta && <HeroFact label="Est. completion" value={eta} />}
              {currentStrategy && <HeroFact label="Current strategy" value={`${countryFlag(currentStrategy)} ${currentStrategy} First`} />}
              {alternativeStrategy && <HeroFact label="Alternative" value={`${countryFlag(alternativeStrategy)} ${alternativeStrategy}`} />}
            </div>

            <div className="mt-6 flex gap-2">
              <Button size="sm" variant="outline" className="border-white/30 bg-white/10 text-white hover:bg-white/20" onClick={onEdit}>
                <Pencil className="h-3.5 w-3.5" /> Edit Mission
              </Button>
              <Button size="sm" variant="outline" className="border-white/30 bg-white/10 text-white hover:bg-white/20" loading={pausePending} onClick={onPause}>
                <Pause className="h-3.5 w-3.5" /> {mission.status === 'PAUSED' ? 'Resume Mission' : 'Pause Mission'}
              </Button>
            </div>
          </div>

          <div className="flex shrink-0 items-center gap-6">
            {progressPct != null && <ProgressRing value={progressPct} label="Mission progress" sublabel={progressSource} />}
            {confidencePct != null && <ProgressRing value={confidencePct} label="AI confidence" sublabel={confidenceRowLabel} size={112} />}
          </div>
        </div>

        {recommendationText && (
          <div className="rounded-xl border border-white/15 bg-white/10 p-4 backdrop-blur-sm">
            <p className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-white/70">
              <Sparkles className="h-3.5 w-3.5" /> AI recommendation
            </p>
            <p className="mt-1.5 text-sm leading-relaxed text-white">{recommendationText}</p>
          </div>
        )}
      </div>
    </Card>
  );
});

const confidenceRowLabel = 'AI-estimated';

function HeroFact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[11px] uppercase tracking-wide text-white/60">{label}</p>
      <p className="font-semibold text-white">{value}</p>
    </div>
  );
}

function ProgressRing({ value, label, sublabel, size = 128 }: { value: number; label: string; sublabel?: string; size?: number }) {
  const r = size === 128 ? 52 : 44;
  const c = 2 * Math.PI * r;
  const clamped = Math.max(0, Math.min(100, value));
  const offset = c - (clamped / 100) * c;
  const vb = size === 128 ? 120 : 104;
  return (
    <div className="relative grid shrink-0 place-items-center" style={{ height: size, width: size }}>
      <svg viewBox={`0 0 ${vb} ${vb}`} className="h-full w-full -rotate-90">
        <circle cx={vb / 2} cy={vb / 2} r={r} fill="none" stroke="rgba(255,255,255,0.2)" strokeWidth="9" />
        <motion.circle
          cx={vb / 2} cy={vb / 2} r={r} fill="none" stroke="white" strokeWidth="9" strokeLinecap="round"
          strokeDasharray={c}
          initial={{ strokeDashoffset: c }}
          animate={{ strokeDashoffset: offset }}
          transition={{ duration: 1, ease: 'easeOut' }}
        />
      </svg>
      <div className="absolute flex flex-col items-center px-2 text-center">
        <span className={cn('font-semibold tabular-nums', size === 128 ? 'text-2xl' : 'text-xl')}>{clamped}%</span>
        <span className="text-[10px] uppercase tracking-wide text-white/70">{label}</span>
        {sublabel && <span className="text-[9px] text-white/50">{sublabel}</span>}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Zone 2 — Today's Mission
// ---------------------------------------------------------------------------

function Zone2TodaysMission({
  dailyTasks, dailyLoading, actions, actionsLoading, hasStrategy, onGenerate, generatePending, onComplete, completePending,
}: {
  dailyTasks: string[];
  dailyLoading: boolean;
  actions: StrategyActionResponse[];
  actionsLoading: boolean;
  hasStrategy: boolean;
  onGenerate: () => void;
  generatePending: boolean;
  onComplete: (id: string) => void;
  completePending: boolean;
}) {
  const openActions = actions.filter((a) => a.status !== 'COMPLETED').slice(0, 5);
  const useDaily = dailyTasks.length > 0;
  const completedCount = actions.filter((a) => a.status === 'COMPLETED').length;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <div>
          <CardTitle>Today's mission</CardTitle>
          <p className="text-sm text-muted-foreground">Execution, not analysis.</p>
        </div>
        {actions.length > 0 && (
          <span className="text-sm font-semibold tabular-nums text-foreground">{completedCount}/{actions.length} completed</span>
        )}
      </CardHeader>
      <CardContent>
        {useDaily ? (
          dailyLoading ? <Skeleton className="h-40 w-full" /> : (
            <ul className="space-y-2">
              {dailyTasks.slice(0, 5).map((t, i) => (
                <li key={i} className="flex items-start gap-2 rounded-lg border border-border p-3 text-sm">
                  <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                  <span>{t}</span>
                </li>
              ))}
            </ul>
          )
        ) : actionsLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : !hasStrategy || openActions.length === 0 ? (
          <EmptyState
            compact
            icon={ListChecks}
            title={hasStrategy ? 'All caught up' : 'No plan yet'}
            description={hasStrategy ? 'Every action in your current plan is complete.' : 'Generate a strategy plan to get a real task list.'}
            action={!hasStrategy ? <Button size="sm" loading={generatePending} onClick={onGenerate}><Sparkles className="h-3.5 w-3.5" /> Generate plan</Button> : undefined}
          />
        ) : (
          <ul className="space-y-2" role="list">
            <AnimatePresence initial={false}>
              {openActions.map((a) => (
                <motion.li
                  key={a.id}
                  layout
                  initial={{ opacity: 0, y: -6 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, x: 24, transition: { duration: 0.2 } }}
                  className="flex items-start gap-3 rounded-lg border border-border p-3 text-sm"
                >
                  <button
                    type="button"
                    onClick={() => onComplete(a.id)}
                    disabled={completePending}
                    aria-label={`Mark "${a.title}" complete`}
                    className="mt-0.5 shrink-0 text-muted-foreground transition-colors hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-full disabled:opacity-50"
                  >
                    <Circle className="h-[18px] w-[18px]" />
                  </button>
                  <div className="min-w-0 flex-1">
                    <p className="font-medium text-foreground">{a.title}</p>
                    {a.targetDate && (
                      <p className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground">
                        <CalendarClock className="h-3 w-3" /> Due {new Date(a.targetDate).toLocaleDateString()}
                      </p>
                    )}
                  </div>
                </motion.li>
              ))}
            </AnimatePresence>
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Zone 3 — Dream Journey (real, timestamped mission events; horizontal)
// ---------------------------------------------------------------------------

function Zone3DreamJourney({ events }: { events: { label: string; at: string; icon: typeof Flag; tone: BadgeTone }[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Dream journey</CardTitle>
        <p className="text-sm text-muted-foreground">Real, timestamped milestones for this mission.</p>
      </CardHeader>
      <CardContent>
        {events.length === 0 ? (
          <EmptyState compact icon={Compass} title="No activity yet" />
        ) : (
          <div className="flex gap-6 overflow-x-auto pb-2" role="list">
            {events.map((e, i) => (
              <motion.div
                key={`${e.label}-${e.at}`}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: i * 0.06 }}
                role="listitem"
                className="flex min-w-[10rem] shrink-0 flex-col items-center text-center"
              >
                <Tooltip content={new Date(e.at).toLocaleString()}>
                  <span
                    tabIndex={0}
                    className={cn(
                      'flex h-11 w-11 items-center justify-center rounded-full ring-4 ring-card',
                      e.tone === 'success' && 'bg-success/15 text-success',
                      e.tone === 'primary' && 'bg-primary/15 text-primary',
                      e.tone === 'info' && 'bg-secondary/15 text-secondary',
                    )}
                  >
                    <e.icon className="h-5 w-5" />
                  </span>
                </Tooltip>
                <p className="mt-2 max-w-[9rem] text-xs font-medium text-foreground">{e.label}</p>
                <p className="text-[11px] text-muted-foreground">{new Date(e.at).toLocaleDateString()}</p>
                {i < events.length - 1 && <div className="mt-2 h-px w-full bg-border" aria-hidden="true" />}
              </motion.div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Zone 4 — AI Coach (one recommendation)
// ---------------------------------------------------------------------------

function Zone4AiCoach({
  recommendationText, decisions, generatedAt, isLoading, onRefresh, refreshPending,
}: {
  recommendationText: string | null;
  decisions: WorkflowDecisionResponse[];
  generatedAt?: string;
  isLoading: boolean;
  onRefresh: () => void;
  refreshPending: boolean;
}) {
  const [showWhy, setShowWhy] = useState(false);
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle>CareerPilot AI recommends</CardTitle>
        <Button size="sm" variant="outline" loading={refreshPending} onClick={onRefresh}>
          <RefreshCw className="h-3.5 w-3.5" /> Refresh
        </Button>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-32 w-full" />
        ) : !recommendationText ? (
          <EmptyState compact icon={Compass} title="No recommendation yet" description="Refresh to get a prioritized recommendation." />
        ) : (
          <div className="space-y-3">
            <p className="text-sm leading-relaxed text-foreground">{recommendationText}</p>
            {decisions.length > 0 && (
              <>
                <button
                  type="button"
                  onClick={() => setShowWhy((v) => !v)}
                  className="text-xs font-medium text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded"
                  aria-expanded={showWhy}
                >
                  {showWhy ? 'Hide reasoning' : 'Why?'}
                </button>
                <AnimatePresence initial={false}>
                  {showWhy && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: 'auto', opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      className="space-y-1.5 overflow-hidden border-t border-border pt-3"
                    >
                      {decisions.map((d) => (
                        <div key={d.workflowId} className="flex items-start gap-2 text-xs">
                          <Badge tone="primary" className="shrink-0">{d.workflowId}</Badge>
                          <span className="text-muted-foreground">{d.reason}</span>
                        </div>
                      ))}
                    </motion.div>
                  )}
                </AnimatePresence>
              </>
            )}
            {generatedAt && <p className="text-xs text-muted-foreground">Generated {new Date(generatedAt).toLocaleString()}</p>}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// "AI Working Now" + "Mission Pipeline" + "AI Team" — all three read the SAME
// real per-stage telemetry (`WorkflowAgent[]`, live-polled) for the user's
// most recent workflow run, just presented three different ways. No new data
// source, no fabricated progress/ETA.
// ---------------------------------------------------------------------------

function AiActivitySection({ latestRun }: { latestRun: WorkflowRun | undefined }) {
  const { data } = useWorkflowStatus(latestRun?.threadId);
  const agents = data?.agents ?? latestRun?.agents ?? [];
  const runStatus = data?.status ?? latestRun?.status;

  if (!latestRun || agents.length === 0) {
    return (
      <Card>
        <CardHeader><CardTitle>AI activity</CardTitle></CardHeader>
        <CardContent>
          <EmptyState compact icon={Sparkles} title="No AI activity yet" description="Run the AI workflow to see your team working in real time." action={<Link to="/workflow"><Button size="sm"><Sparkles className="h-3.5 w-3.5" /> Run AI workflow</Button></Link>} />
        </CardContent>
      </Card>
    );
  }

  const completed = agents.filter((a) => a.status === 'COMPLETED').length;
  const current = currentStage(agents);
  const pct = Math.round((completed / agents.length) * 100);
  const isRunning = runStatus === 'RUNNING' || current?.status === 'ACTIVE';
  const currentPersona = current ? AGENT_PERSONAS[current.name] : undefined;

  return (
    <div className="space-y-4">
      {/* AI Working Now */}
      <Card className={cn('overflow-hidden', isRunning && 'border-primary/40')}>
        <CardContent className="p-5">
          <div className="flex items-center justify-between">
            <p className="flex items-center gap-2 text-sm font-semibold text-foreground">
              {isRunning && <span className="relative flex h-2 w-2"><span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-primary opacity-75" /><span className="relative inline-flex h-2 w-2 rounded-full bg-primary" /></span>}
              {isRunning ? 'CareerPilot AI is working…' : 'Last AI activity'}
            </p>
            <Badge tone={current ? AGENT_STATUS_TONE[current.status] : 'neutral'}>{current ? AGENT_STATUS_LABEL[current.status] : '—'}</Badge>
          </div>
          <p className="mt-2 text-lg font-semibold text-foreground">
            {currentPersona ? `${currentPersona.emoji} ${current!.name}` : current?.name}
          </p>
          <div className="mt-3">
            <div className="mb-1 flex items-center justify-between text-xs text-muted-foreground">
              <span>{completed} of {agents.length} stages complete</span>
              <span className="font-semibold tabular-nums text-foreground">{pct}%</span>
            </div>
            <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
              <motion.div className="h-full rounded-full bg-primary" initial={{ width: 0 }} animate={{ width: `${pct}%` }} transition={{ duration: 0.8, ease: 'easeOut' }} />
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Mission Pipeline */}
      <Card>
        <CardHeader><CardTitle>Mission pipeline</CardTitle></CardHeader>
        <CardContent>
          <div className="flex items-center gap-1 overflow-x-auto pb-2" role="list" aria-label="Workflow pipeline stages">
            {agents.map((a, i) => (
              <div key={a.name} className="flex items-center gap-1">
                <Tooltip content={a.durationMs ? `${(a.durationMs / 1000).toFixed(1)}s${a.provider ? ` · ${a.provider}` : ''}` : AGENT_STATUS_LABEL[a.status]}>
                  <span
                    role="listitem"
                    tabIndex={0}
                    className={cn(
                      'whitespace-nowrap rounded-full border px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      a.status === 'COMPLETED' && 'border-success/30 bg-success/10 text-success',
                      a.status === 'ACTIVE' && 'border-primary/40 bg-primary/10 text-primary',
                      a.status === 'WAITING_FOR_APPROVAL' && 'border-warning/30 bg-warning/10 text-warning',
                      a.status === 'PENDING' && 'border-border bg-muted/40 text-muted-foreground',
                      (a.status === 'FAILED' || a.status === 'REJECTED') && 'border-danger/30 bg-danger/10 text-danger',
                    )}
                  >
                    {AGENT_PERSONAS[a.name]?.emoji} {a.name}
                  </span>
                </Tooltip>
                {i < agents.length - 1 && <span className="h-px w-4 shrink-0 bg-border" aria-hidden="true" />}
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* AI Team */}
      <Card>
        <CardHeader><CardTitle>Your AI team</CardTitle></CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {agents.map((a) => {
              const persona = AGENT_PERSONAS[a.name];
              return (
                <div key={a.name} className="rounded-xl border border-border p-3 text-center">
                  <div className="text-2xl">{persona?.emoji ?? '🤖'}</div>
                  <p className="mt-1 text-xs font-medium text-foreground">{persona?.persona ?? a.name}</p>
                  <Badge tone={AGENT_STATUS_TONE[a.status]} className="mt-1.5">{AGENT_STATUS_LABEL[a.status]}</Badge>
                </div>
              );
            })}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Workflow Queue — real recent WorkflowRuns, grouped by status
// ---------------------------------------------------------------------------

function WorkflowQueueCard({ runs, isLoading }: { runs: WorkflowRun[]; isLoading: boolean }) {
  return (
    <Card>
      <CardHeader><CardTitle>Workflow queue</CardTitle></CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-32 w-full" />
        ) : runs.length === 0 ? (
          <EmptyState compact icon={Activity} title="No runs yet" />
        ) : (
          <ul className="space-y-2">
            {runs.slice(0, 6).map((r) => (
              <li key={r.threadId} className="flex items-center justify-between gap-3 rounded-lg border border-border p-3 text-sm">
                <div className="min-w-0">
                  <p className="truncate font-medium text-foreground">{r.targetRole || 'Career workflow'}</p>
                  {r.createdAt && <p className="text-xs text-muted-foreground">{new Date(r.createdAt).toLocaleString()}</p>}
                </div>
                <Badge tone={runStatusTone(r.status)} className="shrink-0">{runStatusLabel(r.status)}</Badge>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Career Memory
// ---------------------------------------------------------------------------

function CareerMemoryCard({ memories, isLoading }: { memories: CareerDecisionMemory[]; isLoading: boolean }) {
  const values = memories.map((m) => m.value).filter((v): v is string => !!v);
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><Brain className="h-4 w-4 text-primary" /> CareerPilot remembers</CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-16 w-full" />
        ) : values.length === 0 ? (
          <EmptyState compact icon={Brain} title="Nothing learned yet" description="CareerPilot builds this up from your missions, feedback, and workflow runs over time." />
        ) : (
          <div className="flex flex-wrap gap-2">
            {values.slice(0, 16).map((v, i) => (
              <Badge key={i} tone="neutral"><CheckCircle2 className="h-3 w-3" /> {v}</Badge>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Operations Indicator — small, top-right. Only shows what a real endpoint
// reports; MCP and Spring AI have no health endpoint anywhere in the backend
// (confirmed by grep) so they're intentionally not represented as fake dots.
// ---------------------------------------------------------------------------

const OPS_HEALTH_ICON: Record<string, typeof CheckCircle2> = { UP: CheckCircle2, DEGRADED: Clock, DOWN: XCircle, NOT_CONFIGURED: Circle };

function OperationsIndicator({ overall }: { overall?: string }) {
  if (!overall) return null;
  const tone = healthTone(overall);
  const Icon = OPS_HEALTH_ICON[overall] ?? Circle;
  return (
    <Tooltip content="AI platform status">
      <Badge tone={tone} className="cursor-default"><Icon className="h-3 w-3" /> Platform {overall === 'UP' ? 'online' : overall.toLowerCase()}</Badge>
    </Tooltip>
  );
}

// ---------------------------------------------------------------------------
// Zone 5 — Mission Health (compact clickable cards, real dashboard scores)
// ---------------------------------------------------------------------------

function Zone5MissionHealth({ dashboard, isLoading }: { dashboard: DashboardSnapshot | undefined; isLoading: boolean }) {
  const lastUpdated = dashboard?.recentRuns?.[0]?.createdAt;
  const cards = dashboard
    ? [
        { label: 'Resume', value: dashboard.resumeScore, to: '/resumes', icon: Briefcase },
        { label: 'ATS', value: dashboard.atsScore, to: '/resumes', icon: Target },
        { label: 'Job match', value: dashboard.jobMatchScore, to: '/jobs', icon: Globe2 },
        { label: 'Interview', value: dashboard.interviewReadinessScore, to: '/stories', icon: Users },
        { label: 'Offer probability', value: dashboard.offerProbabilityScore, to: '/offers', icon: Trophy },
      ]
    : [];

  return (
    <div>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-base font-semibold text-foreground">Mission health</h2>
        {lastUpdated && <span className="text-xs text-muted-foreground">Updated {new Date(lastUpdated).toLocaleDateString()}</span>}
      </div>
      {isLoading ? (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
          {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-28 rounded-xl" />)}
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
          {cards.map((c) => (
            <Link key={c.label} to={c.to} aria-label={`${c.label}: ${c.value} percent — view details`}>
              <motion.div whileHover={{ y: -3 }} transition={{ duration: 0.18 }}>
                <Card className="p-4 transition-shadow hover:shadow-md">
                  <div className={cn('flex h-9 w-9 items-center justify-center rounded-lg', SCORE_TONE_CLASSES[scoreTone(c.value)])}>
                    <c.icon className="h-4 w-4" />
                  </div>
                  <p className="mt-3 text-2xl font-semibold tabular-nums text-foreground">{c.value}%</p>
                  <p className="text-sm text-muted-foreground">{c.label}</p>
                </Card>
              </motion.div>
            </Link>
          ))}
        </div>
      )}
      <p className="mt-2 text-xs text-muted-foreground">
        Learning and networking signals aren't tracked by the platform yet — shown here only once a real data source exists.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Country strategy (real career-fit data, only rendered when present)
// ---------------------------------------------------------------------------

function CountryStrategyCard({ countries, profiles, currentStrategy, alternativeStrategy }: {
  countries: CareerFitResponse[];
  profiles: CountryProfileResponse[];
  currentStrategy?: string | null;
  alternativeStrategy?: string | null;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><Globe2 className="h-4 w-4 text-primary" /> Country strategy</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {countries.map((c) => {
            const isCurrent = currentStrategy?.toLowerCase() === c.displayName.toLowerCase();
            const isAlt = alternativeStrategy?.toLowerCase() === c.displayName.toLowerCase();
            const profile = profiles.find((p) => p.countryCode === c.countryCode);
            return (
              <div key={c.countryCode} className={cn('rounded-xl border p-4', isCurrent ? 'border-primary bg-primary/5' : 'border-border')}>
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-foreground">{countryFlag(c.displayName)} {c.displayName}</span>
                  <span className="text-lg font-semibold tabular-nums text-primary">{c.fitScore}%</span>
                </div>
                <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{c.explanation}</p>
                {profile && (profile.jobStabilityScore != null || profile.visaProbabilityScore != null) && (
                  <div className="mt-2 flex gap-3 text-xs text-muted-foreground">
                    {profile.jobStabilityScore != null && <span className="flex items-center gap-1"><Briefcase className="h-3 w-3" /> Jobs {profile.jobStabilityScore}</span>}
                    {profile.visaProbabilityScore != null && <span className="flex items-center gap-1"><MapPin className="h-3 w-3" /> Visa {profile.visaProbabilityScore}</span>}
                  </div>
                )}
                {(isCurrent || isAlt) && <Badge tone={isCurrent ? 'primary' : 'neutral'} className="mt-2">{isCurrent ? 'Current strategy' : 'Alternative'}</Badge>}
              </div>
            );
          })}
        </div>
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Skeleton
// ---------------------------------------------------------------------------

function MissionControlSkeleton() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-8 w-64" />
      <Skeleton className="h-72 w-full rounded-xl" />
      <div className="grid gap-4 lg:grid-cols-2">
        <Skeleton className="h-64 rounded-xl" />
        <Skeleton className="h-64 rounded-xl" />
      </div>
      <Skeleton className="h-40 rounded-xl" />
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-28 rounded-xl" />)}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Real vs. omitted — see the design report delivered alongside this file for
// the full rationale. In short: AI Confidence binds to the real (dark-by-
// default) career-intelligence probability signal, Mission Status is a real
// elapsed-time-vs-progress comparison (not a new backend metric), and Dream
// Journey shows real timestamped mission events rather than the requested
// fictional 8-stage pipeline. Expected Impact %, per-task priority/duration/
// workflow-source, and Learning/Networking health scores have no backend
// source anywhere in this codebase and are omitted rather than invented.
// ---------------------------------------------------------------------------
