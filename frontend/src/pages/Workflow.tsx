import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { motion, AnimatePresence } from 'framer-motion';
import {
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock,
  Loader2,
  Sparkles,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { useWorkflowStatus } from '@/hooks/useWorkflowStatus';
import { PageHeader } from '@/components/common/PageHeader';
import { ErrorBoundary } from '@/components/common/ErrorBoundary';
import { WorkflowForm } from '@/components/workflow/WorkflowForm';
import { WorkflowStatusStepper } from '@/components/workflow/WorkflowStatusStepper';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Input, Label } from '@/components/ui/input';
import { EmptyState } from '@/components/ui/empty-state';
import { cn } from '@/lib/cn';
import type { WorkflowRun } from '@/types/workflow';

const PIPELINE = [
  'Resume Intelligence',
  'Job Discovery',
  'ATS Optimization',
  'Interview Prep',
  'Career Strategy',
  'Salary Intelligence',
  'Human Approval',
  'Application Tracking',
];

const RUN_STATUS: Record<string, { icon: React.ElementType; tone: BadgeTone; label: string; spin?: boolean }> = {
  COMPLETED: { icon: CheckCircle2, tone: 'success', label: 'Completed' },
  ERROR: { icon: XCircle, tone: 'danger', label: 'Failed' },
  FAILED: { icon: XCircle, tone: 'danger', label: 'Failed' },
  REJECTED: { icon: XCircle, tone: 'danger', label: 'Rejected' },
  RUNNING: { icon: Loader2, tone: 'info', label: 'Running', spin: true },
  IN_PROGRESS: { icon: Loader2, tone: 'info', label: 'Running', spin: true },
  INTERRUPTED: { icon: AlertTriangle, tone: 'warning', label: 'Needs approval' },
};

/** Compact human label for a millisecond duration (e.g. "1.4s", "820ms", "2m 5s"). */
function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.round((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}

/** Format an ISO timestamp as a short local date+time, tolerant of bad input. */
function formatWhen(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d);
}

export default function Workflow() {
  const qc = useQueryClient();
  const [targeting, setTargeting] = useState({
    targetRole: 'Senior Software Engineer',
    targetSeniority: 'Senior',
    targetLocations: 'Remote, US',
  });
  const [showAdvanced, setShowAdvanced] = useState(false);

  const { data: runs = [] } = useQuery<WorkflowRun[]>({
    queryKey: ['workflows'],
    queryFn: async () => (await api.get('/api/workflows')).data,
  });

  const resumeMutation = useMutation({
    mutationFn: async ({ threadId, decision }: { threadId: string; decision: 'approved' | 'rejected' }) => {
      await api.post(`/api/workflows/${threadId}/resume`, { decision });
      return threadId;
    },
    onSuccess: (threadId) => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      qc.invalidateQueries({ queryKey: ['workflow-status', threadId] });
      qc.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });

  // Thread id currently being resumed (so only that card shows a spinner).
  const pendingThreadId = resumeMutation.isPending ? resumeMutation.variables?.threadId : undefined;

  const extraPayload = {
    targetRole: targeting.targetRole,
    targetSeniority: targeting.targetSeniority,
    targetLocations: targeting.targetLocations.split(',').map((s) => s.trim()).filter(Boolean),
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title="AI Career Workflow"
        description="Launch and monitor your multi-agent career pipeline — an AI copilot for your job search."
      />

      {/* Pipeline — live for the most recent run, static template when idle */}
      <PipelineOverview latestRun={runs[0]} />

      {/* Start form */}
      <ErrorBoundary>
        <Card>
          <CardHeader>
            <CardTitle>Start a new run</CardTitle>
            <p className="text-sm text-muted-foreground">
              Pick a resume and target jobs for the agents to work through.
            </p>
          </CardHeader>
          <CardContent className="space-y-4">
            <WorkflowForm extraPayload={extraPayload} />

            <div className="rounded-lg border border-border">
              <button
                type="button"
                onClick={() => setShowAdvanced((v) => !v)}
                className="flex w-full items-center justify-between px-4 py-3 text-sm font-medium text-foreground"
              >
                Advanced targeting
                {showAdvanced ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
              </button>
              <AnimatePresence initial={false}>
                {showAdvanced && (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: 'auto', opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.2 }}
                    className="overflow-hidden"
                  >
                    <div className="grid gap-4 border-t border-border p-4 sm:grid-cols-3">
                      {([
                        { key: 'targetRole', label: 'Target role' },
                        { key: 'targetSeniority', label: 'Seniority' },
                        { key: 'targetLocations', label: 'Locations (comma-separated)' },
                      ] as const).map(({ key, label }) => (
                        <div key={key}>
                          <Label>{label}</Label>
                          <Input
                            value={targeting[key]}
                            onChange={(e) => setTargeting((t) => ({ ...t, [key]: e.target.value }))}
                          />
                        </div>
                      ))}
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </CardContent>
        </Card>
      </ErrorBoundary>

      {/* Runs */}
      <section aria-label="Workflow runs" className="space-y-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          Recent runs
        </h2>
        {runs.length === 0 ? (
          <EmptyState
            icon={Sparkles}
            title="No runs yet"
            description="Start a workflow above to see the agent pipeline execute in real time."
          />
        ) : (
          <div className="space-y-3">
            {runs.map((run) => {
              const isResuming = pendingThreadId === run.threadId;
              const resumeError =
                resumeMutation.isError && resumeMutation.variables?.threadId === run.threadId
                  ? resumeMutation.error
                  : null;
              return (
                <RunCard
                  key={run.threadId}
                  run={run}
                  // Double-submit guard (Scenario E): ignore extra clicks while any resume
                  // is in flight. The backend + agent service also reject a second decision
                  // with 409, so this is purely to avoid firing redundant requests.
                  onApprove={(id) => {
                    if (!resumeMutation.isPending) resumeMutation.mutate({ threadId: id, decision: 'approved' });
                  }}
                  onReject={(id) => {
                    if (!resumeMutation.isPending) resumeMutation.mutate({ threadId: id, decision: 'rejected' });
                  }}
                  isResuming={isResuming}
                  resumeError={resumeError}
                />
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}

/**
 * Top-of-page pipeline. When a run exists it reflects that run's *live* state
 * (polled via useWorkflowStatus) — current stage, status, progress, and a
 * horizontal stepper — instead of a static, always-identical chip row. With no
 * runs it falls back to the static template so the page communicates the shape
 * of the pipeline before the first run.
 */
function PipelineOverview({ latestRun }: { latestRun: WorkflowRun | undefined }) {
  // Hook is called unconditionally (disabled when threadId is absent). It shares
  // the ['workflow-status', threadId] query cache with the run card's stepper.
  const { data } = useWorkflowStatus(latestRun?.threadId);
  const agents = data?.agents ?? [];

  if (!latestRun || agents.length === 0) {
    return (
      <Card className="overflow-hidden">
        <CardContent className="p-5">
          <div className="mb-3 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <Sparkles className="h-3.5 w-3.5 text-primary" /> Pipeline
          </div>
          <div className="flex items-center gap-1 overflow-x-auto pb-1">
            {PIPELINE.map((name, i) => (
              <div key={name} className="flex items-center gap-1">
                <span className="whitespace-nowrap rounded-full border border-border bg-muted/40 px-3 py-1 text-xs font-medium text-foreground">
                  {name}
                </span>
                {i < PIPELINE.length - 1 && <span className="h-px w-4 shrink-0 bg-border" />}
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    );
  }

  const total = agents.length;
  const completed = agents.filter((a) => a.status === 'COMPLETED').length;
  // "Current stage" = where attention is owed: a failure first, then an active/awaiting
  // stage, then the next pending one. Surfacing the failed stage (instead of the stage
  // after it) keeps this label consistent with a FAILED run badge.
  const current =
    agents.find((a) => a.status === 'FAILED' || a.status === 'REJECTED') ??
    agents.find((a) => a.status === 'ACTIVE' || a.status === 'WAITING_FOR_APPROVAL') ??
    agents.find((a) => a.status === 'PENDING') ??
    // All stages COMPLETED → the current stage is the final one (Application Tracking),
    // so a finished run reads "Current stage: Application Tracking" rather than "—".
    agents[agents.length - 1];
  const runStatus = data?.status ?? latestRun.status;
  const cfg = RUN_STATUS[runStatus] ?? { icon: Clock, tone: 'neutral' as BadgeTone, label: runStatus };
  const StatusIcon = cfg.icon;
  const pct = total > 0 ? Math.round((completed / total) * 100) : 0;

  return (
    <Card className="overflow-hidden">
      <CardContent className="p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            <Sparkles className="h-3.5 w-3.5 text-primary" /> Pipeline
          </div>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
            <span className="text-muted-foreground">
              Current stage:{' '}
              <span className="font-medium text-foreground">{current?.name ?? '—'}</span>
            </span>
            <Badge tone={cfg.tone}>
              <StatusIcon className={cn('h-3 w-3', cfg.spin && 'animate-spin')} />
              {cfg.label}
            </Badge>
          </div>
        </div>

        <div className="mb-4">
          <div className="mb-1 flex items-center justify-between text-xs text-muted-foreground">
            <span>Progress</span>
            <span className="tabular-nums">
              {completed} / {total} stages
            </span>
          </div>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
            <motion.div
              className="h-full rounded-full bg-primary"
              initial={{ width: 0 }}
              animate={{ width: `${pct}%` }}
              transition={{ duration: 0.4, ease: 'easeOut' }}
            />
          </div>
        </div>

        <WorkflowStatusStepper
          workflowId={latestRun.threadId}
          variant="horizontal"
          className="border-0 bg-transparent p-0"
        />
      </CardContent>
    </Card>
  );
}

function ScorePill({ label, value }: { label: string; value: number | null | undefined }) {
  const v = value ?? null;
  const color =
    v === null ? 'text-muted-foreground' : v >= 80 ? 'text-success' : v >= 60 ? 'text-warning' : 'text-danger';
  return (
    <div className="flex flex-col items-center">
      <span className={cn('text-base font-semibold tabular-nums', color)}>{v ?? '—'}</span>
      <span className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</span>
    </div>
  );
}

function RunCard({
  run,
  onApprove,
  onReject,
  isResuming,
  resumeError,
}: {
  run: WorkflowRun;
  onApprove: (id: string) => void;
  onReject: (id: string) => void;
  isResuming: boolean;
  resumeError: unknown;
}) {
  const [expanded, setExpanded] = useState(false);
  const cfg = RUN_STATUS[run.status] ?? { icon: Clock, tone: 'neutral' as BadgeTone, label: run.status };
  const Icon = cfg.icon;
  const hasScores =
    run.resumeScore != null || run.atsScore != null || run.jobMatchScore != null || run.interviewReadinessScore != null;

  // Phase 9 — at-a-glance health, derived from the same agents[] the timeline renders.
  const agents = run.agents ?? [];
  const currentStage =
    agents.find((a) => a.status === 'FAILED' || a.status === 'REJECTED') ??
    agents.find((a) => a.status === 'ACTIVE' || a.status === 'WAITING_FOR_APPROVAL') ??
    agents.find((a) => a.status === 'PENDING') ??
    agents[agents.length - 1];
  const totalDurationMs = agents.reduce((sum, a) => sum + (a.durationMs ?? 0), 0);
  const updatedLabel = run.updatedAt ? formatWhen(run.updatedAt) : null;
  const isRejected = run.status === 'REJECTED';
  const hasAudit = run.rejectedBy || run.rejectedAt || run.approvedBy || run.approvedAt || run.feedback;

  return (
    <Card className="overflow-hidden">
      <button
        className="flex w-full items-center justify-between gap-4 px-5 py-4 text-left"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
      >
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-3">
          <span className="font-mono text-sm text-muted-foreground" title={run.threadId}>
            #{run.threadId.slice(0, 8)}
          </span>
          {run.targetRole && (
            <span className="truncate text-sm font-medium text-foreground">{run.targetRole}</span>
          )}
          <Badge tone={cfg.tone}>
            <Icon className={cn('h-3 w-3', cfg.spin && 'animate-spin')} />
            {cfg.label}
          </Badge>

          {/* Phase 9 — current stage + last updated, visible without expanding. */}
          {currentStage && run.status !== 'COMPLETED' && (
            <span className="hidden text-xs text-muted-foreground md:inline">
              {currentStage.name}
            </span>
          )}
          {updatedLabel && (
            <span className="hidden items-center gap-1 text-xs text-muted-foreground lg:inline-flex">
              <Clock className="h-3 w-3" /> {updatedLabel}
            </span>
          )}
        </div>

        {hasScores && (
          <div className="hidden items-center gap-5 sm:flex">
            <ScorePill label="Resume" value={run.resumeScore} />
            <ScorePill label="ATS" value={run.atsScore} />
            <ScorePill label="Match" value={run.jobMatchScore} />
            <ScorePill label="Interview" value={run.interviewReadinessScore} />
          </div>
        )}

        <span className="shrink-0 text-muted-foreground">
          {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </span>
      </button>

      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.25, ease: 'easeInOut' }}
            className="overflow-hidden"
          >
            <div className="border-t border-border px-5 py-5">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                <span className="flex items-center gap-2">
                  <BarChart3 className="h-3.5 w-3.5" /> Execution timeline
                </span>
                {totalDurationMs > 0 && (
                  <span className="flex items-center gap-1 normal-case tabular-nums">
                    <Clock className="h-3 w-3" /> {formatDuration(totalDurationMs)} total
                  </span>
                )}
              </div>
              <WorkflowStatusStepper workflowId={run.threadId} variant="vertical" />

              {/* Phase 5 + 6 — rejected/approved audit trail. */}
              {hasAudit && (
                <div
                  className={cn(
                    'mt-4 rounded-lg border px-4 py-3 text-sm',
                    isRejected ? 'border-danger/30 bg-danger/5' : 'border-success/30 bg-success/5',
                  )}
                >
                  <div className="mb-2 flex items-center gap-2 font-medium">
                    {isRejected ? (
                      <>
                        <XCircle className="h-4 w-4 text-danger" />
                        <span className="text-danger">Workflow Rejected</span>
                      </>
                    ) : (
                      <>
                        <CheckCircle2 className="h-4 w-4 text-success" />
                        <span className="text-success">Approved</span>
                      </>
                    )}
                  </div>
                  <dl className="grid gap-x-6 gap-y-1 text-xs text-muted-foreground sm:grid-cols-2">
                    {(isRejected ? run.rejectedBy : run.approvedBy) && (
                      <div className="flex gap-1.5">
                        <dt className="font-medium text-foreground">{isRejected ? 'Rejected by' : 'Approved by'}:</dt>
                        <dd>{isRejected ? run.rejectedBy : run.approvedBy}</dd>
                      </div>
                    )}
                    {(isRejected ? run.rejectedAt : run.approvedAt) && (
                      <div className="flex gap-1.5">
                        <dt className="font-medium text-foreground">{isRejected ? 'Rejected at' : 'Approved at'}:</dt>
                        <dd>{formatWhen((isRejected ? run.rejectedAt : run.approvedAt) as string)}</dd>
                      </div>
                    )}
                    {run.feedback && (
                      <div className="flex gap-1.5 sm:col-span-2">
                        <dt className="font-medium text-foreground">Feedback:</dt>
                        <dd className="italic">"{run.feedback}"</dd>
                      </div>
                    )}
                  </dl>
                </div>
              )}

              {run.status === 'INTERRUPTED' && (
                <div className="mt-4 space-y-2">
                  <div
                    className="flex flex-wrap items-center gap-3 rounded-lg border border-warning/30 bg-warning/10 px-4 py-3"
                    role="alert"
                  >
                    <AlertTriangle className="h-4 w-4 shrink-0 text-warning" />
                    <span className="flex-1 text-sm text-warning">
                      {isResuming
                        ? 'Submitting your decision — the agent is finishing the run…'
                        : 'This workflow requires your approval to continue.'}
                    </span>
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        variant="success"
                        loading={isResuming}
                        disabled={isResuming}
                        onClick={() => onApprove(run.threadId)}
                      >
                        Approve
                      </Button>
                      <Button
                        size="sm"
                        variant="danger"
                        disabled={isResuming}
                        onClick={() => onReject(run.threadId)}
                      >
                        Reject
                      </Button>
                    </div>
                  </div>
                  {resumeError != null && (
                    <div
                      className="flex items-center gap-2 rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger"
                      role="alert"
                    >
                      <XCircle className="h-4 w-4 shrink-0" />
                      <span>
                        {(resumeError as { response?: { data?: { message?: string } }; message?: string })?.response
                          ?.data?.message ??
                          (resumeError as { message?: string })?.message ??
                          'Failed to submit decision. Please try again.'}
                      </span>
                    </div>
                  )}
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </Card>
  );
}
