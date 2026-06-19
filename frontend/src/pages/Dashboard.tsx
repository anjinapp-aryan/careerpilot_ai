import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip as RTooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  Activity,
  ArrowRight,
  Briefcase,
  FileText,
  Gauge,
  Lightbulb,
  Send,
  Sparkles,
  Target,
  TrendingUp,
} from 'lucide-react';
import { api } from '@/lib/api';
import { useAuthStore } from '@/lib/auth';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { CopilotAvatar } from '@/components/copilot/CopilotAvatar';
import type { WorkflowRun } from '@/types/workflow';

interface Snapshot {
  careerHealthScore: number;
  resumeScore: number;
  atsScore: number;
  jobMatchScore: number;
  interviewReadinessScore: number;
  offerProbabilityScore: number;
  applications: Record<string, number>;
  resumes: number;
  recentRuns: WorkflowRun[];
}

const RUN_TONE: Record<string, string> = {
  COMPLETED: 'success',
  ERROR: 'danger',
  FAILED: 'danger',
  RUNNING: 'info',
  IN_PROGRESS: 'info',
  INTERRUPTED: 'warning',
};

const INSIGHT_TONE: Record<string, string> = {
  primary: 'bg-primary/10 text-primary',
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/10 text-warning',
  danger: 'bg-danger/10 text-danger',
  info: 'bg-secondary/10 text-secondary',
};

function scoreTone(v: number): 'success' | 'warning' | 'danger' {
  if (v >= 80) return 'success';
  if (v >= 60) return 'warning';
  return 'danger';
}

export default function Dashboard() {
  const user = useAuthStore((s) => s.user);
  const { data, isLoading } = useQuery<Snapshot>({
    queryKey: ['dashboard'],
    queryFn: async () => (await api.get('/api/dashboard')).data,
  });

  // Score progression from runs (oldest → newest), plus deltas vs previous run.
  const { trend, deltas } = useMemo(() => {
    const runs = [...(data?.recentRuns ?? [])].reverse();
    const trend = runs
      .filter((r) => r.atsScore != null || r.resumeScore != null || r.jobMatchScore != null)
      .map((r, i) => ({
        name: `Run ${i + 1}`,
        ATS: r.atsScore ?? 0,
        Resume: r.resumeScore ?? 0,
        Match: r.jobMatchScore ?? 0,
      }));
    const latest = data?.recentRuns?.[0];
    const prev = data?.recentRuns?.[1];
    const d = (a?: number | null, b?: number | null) =>
      a != null && b != null ? a - b : null;
    return {
      trend,
      deltas: {
        ats: d(latest?.atsScore, prev?.atsScore),
        resume: d(latest?.resumeScore, prev?.resumeScore),
        match: d(latest?.jobMatchScore, prev?.jobMatchScore),
        health: d(
          latest ? latest.atsScore : null,
          prev ? prev.atsScore : null,
        ),
      },
    };
  }, [data]);

  const apps = data?.applications ?? {};
  const pipeline = [
    { name: 'Saved', value: apps.saved ?? 0, tone: 'hsl(var(--muted-foreground))' },
    { name: 'Applied', value: apps.applied ?? 0, tone: 'hsl(var(--secondary))' },
    { name: 'Interview', value: apps.interviewing ?? 0, tone: 'hsl(var(--primary))' },
    { name: 'Offer', value: apps.offer ?? 0, tone: 'hsl(var(--success))' },
    { name: 'Rejected', value: apps.rejected ?? 0, tone: 'hsl(var(--danger))' },
  ];
  const submitted =
    (apps.applied ?? 0) + (apps.interviewing ?? 0) + (apps.offer ?? 0) + (apps.rejected ?? 0);
  const interviewRate = submitted > 0 ? Math.round(((apps.interviewing ?? 0) / submitted) * 100) : 0;

  const insights = useMemo(() => buildInsights(data, deltas), [data, deltas]);

  if (isLoading || !data) return <DashboardSkeleton />;

  const firstName = (user?.fullName || user?.email || 'there').split(/[ @]/)[0];

  return (
    <div className="space-y-6">
      {/* Welcome banner */}
      <Card className="relative overflow-hidden border-0 bg-gradient-to-br from-primary via-primary to-secondary text-primary-foreground shadow-md">
        <div className="pointer-events-none absolute -right-12 -top-16 h-56 w-56 rounded-full bg-white/10 blur-2xl" />
        <div className="pointer-events-none absolute -bottom-20 right-32 h-48 w-48 rounded-full bg-white/10 blur-3xl" />
        <div className="relative flex flex-col gap-6 p-6 md:flex-row md:items-center md:justify-between md:p-8">
          <div className="max-w-xl">
            <Badge className="bg-white/15 text-white ring-white/20">
              <Sparkles className="h-3 w-3" /> AI Career Copilot
            </Badge>
            <h1 className="mt-3 text-2xl font-semibold tracking-tight md:text-3xl">
              Welcome back, {firstName}
            </h1>
            <p className="mt-1.5 text-sm text-white/80">
              Here's how your career is trending. Run the AI pipeline to keep your
              scores climbing and your pipeline moving.
            </p>
            <div className="mt-5 flex flex-wrap gap-3">
              <Link to="/workflow">
                <Button className="bg-white text-primary hover:bg-white/90">
                  <Sparkles className="h-4 w-4" /> Run AI workflow
                </Button>
              </Link>
              <Link to="/jobs">
                <Button variant="outline" className="border-white/30 bg-white/10 text-white hover:bg-white/20">
                  Browse jobs <ArrowRight className="h-4 w-4" />
                </Button>
              </Link>
            </div>
          </div>

          <CareerHealthRing value={data.careerHealthScore} />
        </div>
      </Card>

      {/* KPI grid */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-3 xl:grid-cols-6">
        <KpiCard label="Career Health" value={data.careerHealthScore} suffix="/100" icon={Gauge} tone="primary" hint="Composite of all signals" />
        <KpiCard label="ATS Score" value={data.atsScore} suffix="/100" icon={Target} tone={scoreTone(data.atsScore) === 'success' ? 'success' : 'warning'} delta={deltas.ats} />
        <KpiCard label="Resume Score" value={data.resumeScore} suffix="/100" icon={FileText} tone="info" delta={deltas.resume} />
        <KpiCard label="Job Match" value={data.jobMatchScore} suffix="/100" icon={Briefcase} tone="primary" delta={deltas.match} />
        <KpiCard label="Applications" value={submitted} icon={Send} tone="info" hint={`${apps.saved ?? 0} saved`} />
        <KpiCard label="Interview Rate" value={interviewRate} suffix="%" icon={TrendingUp} tone="success" hint={`${apps.interviewing ?? 0} active`} />
      </div>

      {/* Charts */}
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader className="flex-row items-center justify-between">
            <div>
              <CardTitle>Score progression</CardTitle>
              <p className="text-sm text-muted-foreground">ATS, resume & match across recent runs</p>
            </div>
            <Activity className="h-5 w-5 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {trend.length === 0 ? (
              <EmptyState
                compact
                icon={Activity}
                title="No run history yet"
                description="Start an AI workflow to begin tracking your score trends."
              />
            ) : (
              <ResponsiveContainer width="100%" height={260}>
                <AreaChart data={trend} margin={{ left: -16, right: 8, top: 8 }}>
                  <defs>
                    <linearGradient id="gAts" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity={0.35} />
                      <stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
                  <XAxis dataKey="name" tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} />
                  <YAxis domain={[0, 100]} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} width={40} />
                  <RTooltip content={<ChartTooltip />} />
                  <Area type="monotone" dataKey="ATS" stroke="hsl(var(--primary))" strokeWidth={2.5} fill="url(#gAts)" />
                  <Area type="monotone" dataKey="Resume" stroke="hsl(var(--secondary))" strokeWidth={2} fill="transparent" />
                  <Area type="monotone" dataKey="Match" stroke="hsl(var(--success))" strokeWidth={2} fill="transparent" />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Application pipeline</CardTitle>
            <p className="text-sm text-muted-foreground">Distribution by stage</p>
          </CardHeader>
          <CardContent>
            {submitted + (apps.saved ?? 0) === 0 ? (
              <EmptyState
                compact
                icon={Briefcase}
                title="No applications yet"
                description="Save jobs and track them through the pipeline."
              />
            ) : (
              <ResponsiveContainer width="100%" height={260}>
                <BarChart data={pipeline} margin={{ left: -20, right: 8, top: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
                  <XAxis dataKey="name" tickLine={false} axisLine={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
                  <YAxis allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} width={32} />
                  <RTooltip content={<ChartTooltip />} cursor={{ fill: 'hsl(var(--muted))' }} />
                  <Bar dataKey="value" radius={[6, 6, 0, 0]} maxBarSize={44}>
                    {pipeline.map((p) => (
                      <Cell key={p.name} fill={p.tone} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>
      </div>

      {/* AI insights + recent runs */}
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <CardHeader className="flex-row items-center gap-2">
            <CopilotAvatar size={30} animated={false} />
            <CardTitle>AI Insights</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {insights.map((ins, i) => (
              <motion.div
                key={ins.title}
                initial={{ opacity: 0, x: -6 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: i * 0.05 }}
                className="flex gap-3 rounded-lg border border-border bg-muted/30 p-3"
              >
                <span className={`mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md ${INSIGHT_TONE[ins.tone] ?? INSIGHT_TONE.primary}`}>
                  <ins.icon className="h-4 w-4" />
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-medium text-foreground">{ins.title}</p>
                  <p className="text-xs text-muted-foreground">{ins.body}</p>
                </div>
              </motion.div>
            ))}
          </CardContent>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle>Recent AI workflow runs</CardTitle>
            <Link to="/workflow" className="text-sm font-medium text-primary hover:underline">
              View all
            </Link>
          </CardHeader>
          <CardContent className="pt-0">
            {data.recentRuns.length === 0 ? (
              <EmptyState
                compact
                icon={Sparkles}
                title="No workflow runs yet"
                description="Launch the multi-agent pipeline to optimize your resume and match jobs."
                action={
                  <Link to="/workflow">
                    <Button size="sm"><Sparkles className="h-4 w-4" /> Start a run</Button>
                  </Link>
                }
              />
            ) : (
              <div className="divide-y divide-border">
                {data.recentRuns.slice(0, 6).map((r) => (
                  <div key={r.threadId} className="flex items-center justify-between gap-3 py-3">
                    <div className="flex min-w-0 items-center gap-3">
                      <span className="font-mono text-xs text-muted-foreground">#{r.threadId.slice(0, 8)}</span>
                      <span className="truncate text-sm font-medium text-foreground">
                        {r.targetRole || 'Career workflow'}
                      </span>
                    </div>
                    <div className="flex shrink-0 items-center gap-3">
                      {r.atsScore != null && (
                        <span className="hidden text-xs text-muted-foreground sm:block">
                          ATS <span className="font-semibold text-foreground">{r.atsScore}</span>
                        </span>
                      )}
                      <Badge tone={(RUN_TONE[r.status] as any) ?? 'neutral'}>{r.status}</Badge>
                      {r.createdAt && (
                        <span className="hidden text-xs text-muted-foreground md:block">
                          {new Date(r.createdAt).toLocaleDateString()}
                        </span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Career health ring
// ---------------------------------------------------------------------------

function CareerHealthRing({ value }: { value: number }) {
  const r = 52;
  const c = 2 * Math.PI * r;
  const offset = c - (Math.max(0, Math.min(100, value)) / 100) * c;
  return (
    <div className="relative grid h-36 w-36 shrink-0 place-items-center">
      <svg viewBox="0 0 120 120" className="h-full w-full -rotate-90">
        <circle cx="60" cy="60" r={r} fill="none" stroke="rgba(255,255,255,0.2)" strokeWidth="10" />
        <motion.circle
          cx="60"
          cy="60"
          r={r}
          fill="none"
          stroke="white"
          strokeWidth="10"
          strokeLinecap="round"
          strokeDasharray={c}
          initial={{ strokeDashoffset: c }}
          animate={{ strokeDashoffset: offset }}
          transition={{ duration: 1, ease: 'easeOut' }}
        />
      </svg>
      <div className="absolute flex flex-col items-center">
        <span className="text-3xl font-semibold tabular-nums">{value}</span>
        <span className="text-[11px] uppercase tracking-wide text-white/70">Health</span>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Chart tooltip
// ---------------------------------------------------------------------------

function ChartTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border border-border bg-popover px-3 py-2 text-xs shadow-lg">
      <p className="mb-1 font-medium text-foreground">{label}</p>
      {payload.map((p: any) => (
        <p key={p.name} className="flex items-center gap-1.5 text-muted-foreground">
          <span className="inline-block h-2 w-2 rounded-full" style={{ background: p.color || p.fill }} />
          {p.name}: <span className="font-semibold text-foreground">{p.value}</span>
        </p>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Insights derivation (honest, data-driven)
// ---------------------------------------------------------------------------

function buildInsights(
  data: Snapshot | undefined,
  deltas: { ats: number | null; resume: number | null; match: number | null },
) {
  if (!data) return [];
  const out: { title: string; body: string; icon: typeof Target; tone: string }[] = [];

  if (data.resumes === 0) {
    out.push({
      title: 'Upload your first resume',
      body: 'Add a resume to unlock ATS analysis and AI workflows.',
      icon: FileText,
      tone: 'primary',
    });
  }
  if (typeof deltas.ats === 'number' && deltas.ats > 0) {
    out.push({
      title: `ATS score improved by ${deltas.ats}`,
      body: 'Your latest optimization is paying off — keep iterating.',
      icon: TrendingUp,
      tone: 'success',
    });
  }
  if (data.atsScore > 0 && data.atsScore < 70) {
    out.push({
      title: 'ATS score below target',
      body: 'Run ATS optimization to lift your resume above the 70 threshold.',
      icon: Target,
      tone: 'warning',
    });
  }
  if ((data.applications?.interviewing ?? 0) > 0) {
    out.push({
      title: `${data.applications.interviewing} interview${data.applications.interviewing === 1 ? '' : 's'} in progress`,
      body: 'Open Interview Prep to rehearse role-specific questions.',
      icon: Sparkles,
      tone: 'info',
    });
  }
  if (data.jobMatchScore >= 80) {
    out.push({
      title: 'Strong job matches available',
      body: 'Your profile aligns well with current openings — apply now.',
      icon: Briefcase,
      tone: 'success',
    });
  }

  if (out.length === 0) {
    out.push({
      title: "You're on track",
      body: 'No urgent actions. Run a workflow to surface new recommendations.',
      icon: Lightbulb,
      tone: 'primary',
    });
  }
  return out.slice(0, 4);
}

// ---------------------------------------------------------------------------
// Loading skeleton
// ---------------------------------------------------------------------------

function DashboardSkeleton() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-44 w-full rounded-xl" />
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-3 xl:grid-cols-6">
        {Array.from({ length: 6 }).map((_, i) => (
          <Skeleton key={i} className="h-32 rounded-xl" />
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Skeleton className="h-80 rounded-xl lg:col-span-2" />
        <Skeleton className="h-80 rounded-xl" />
      </div>
    </div>
  );
}
