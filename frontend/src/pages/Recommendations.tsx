import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import {
  Archive,
  Bookmark,
  Brain,
  Building2,
  CheckCircle2,
  ClipboardList,
  HelpCircle,
  RefreshCw,
  Sparkles,
  Star,
  ThumbsDown,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { Tabs } from '@/components/ui/tabs';
import { useToast } from '@/components/ui/toast';
import { Dialog, DialogBody, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { JobBadges } from '@/components/jobs/JobBadges';
import { RelevanceDrawer } from '@/components/jobs/RelevanceDrawer';
import type {
  BehaviorProfile,
  RecommendationAuditRow,
  RecommendationFeedbackRow,
  RecommendedJob,
  RecommendedJobsResponse,
} from '@/types/workflow';

type WorkspaceTab = 'all' | 'must-apply' | 'high-priority' | 'human-review' | 'audit' | 'behavior';

const TABS: { value: WorkspaceTab; label: string }[] = [
  { value: 'all', label: 'Recommended' },
  { value: 'must-apply', label: 'Must Apply' },
  { value: 'high-priority', label: 'High Priority' },
  { value: 'human-review', label: 'Human Review' },
  { value: 'audit', label: 'Audit' },
  { value: 'behavior', label: 'Behavior' },
];

type DecisionAction = 'approve' | 'reject' | 'save' | 'archive';

const ACTION_LABEL: Record<DecisionAction, string> = {
  approve: 'Approved',
  reject: 'Rejected',
  save: 'Saved',
  archive: 'Archived',
};

/**
 * Phase 2C Recommendation Intelligence workspace. Every surface here is a real, already-shipped
 * backend contract: GET /api/recommendations (+filter), the approve/reject/save/archive decision
 * endpoints, the recommendation_audit trail, and the behavior profile. The whole engine ships
 * dark — every query degrades to a quiet empty state when its flag is off.
 */
export default function Recommendations() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [tab, setTab] = useState<WorkspaceTab>('all');
  const [relevanceJob, setRelevanceJob] = useState<{ id: string; title: string } | null>(null);
  const [auditJob, setAuditJob] = useState<{ id: string; title: string } | null>(null);

  const isListTab = tab === 'all' || tab === 'must-apply' || tab === 'high-priority' || tab === 'human-review';

  const list = useQuery<RecommendedJobsResponse | null>({
    queryKey: ['recommendations', tab],
    queryFn: async () => {
      try {
        return (await api.get('/api/recommendations', { params: { filter: tab, size: 25 } })).data;
      } catch {
        return null;
      }
    },
    enabled: isListTab,
    retry: false,
    staleTime: 30_000,
  });

  const decide = useMutation({
    mutationFn: async ({ jobId, action }: { jobId: string; action: DecisionAction }) =>
      (await api.post(`/api/recommendations/${action}`, { jobId })).data,
    onSuccess: (_d, v) => {
      qc.invalidateQueries({ queryKey: ['recommendations'] });
      qc.invalidateQueries({ queryKey: ['applications'] });
      qc.invalidateQueries({ queryKey: ['dashboard'] });
      toast({ variant: 'success', title: ACTION_LABEL[v.action], description: 'Decision recorded to your behavior profile.' });
    },
    onError: (e: any) =>
      toast({
        variant: 'error',
        title: 'Action failed',
        description: e?.response?.status === 404 ? 'Recommendation approval is not enabled yet.' : 'Please try again.',
      }),
  });

  const jobs = list.data?.jobs ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Recommendations"
        description="Review, approve, and curate what the recommendation engine surfaces — every decision teaches your behavior profile."
      />

      <Tabs items={TABS} value={tab} onChange={(v) => setTab(v as WorkspaceTab)} />

      {isListTab && (
        list.isLoading ? (
          <div className="space-y-4">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-40 rounded-xl" />
            ))}
          </div>
        ) : jobs.length === 0 ? (
          <EmptyState
            icon={Star}
            title="Nothing in this collection"
            description={
              list.data == null
                ? 'The recommendation engine is not enabled yet, or your profile has no matches. Run the AI workflow to build your candidate profile.'
                : 'No recommendations in this collection right now — check the other tabs or run a discovery pass.'
            }
          />
        ) : (
          <div className="space-y-4">
            {jobs.map((rec, i) => (
              <RecommendationCard
                key={rec.job.id}
                rec={rec}
                index={i}
                busy={decide.isPending}
                onDecide={(action) => decide.mutate({ jobId: rec.job.id, action })}
                onExplain={() => setRelevanceJob({ id: rec.job.id, title: rec.job.title })}
                onAudit={() => setAuditJob({ id: rec.job.id, title: rec.job.title })}
              />
            ))}
          </div>
        )
      )}

      {tab === 'audit' && <AuditTable onInspect={(jobId) => setAuditJob({ id: jobId, title: 'Recommendation' })} />}
      {tab === 'behavior' && <BehaviorIntelligencePanel />}

      <RelevanceDrawer
        jobId={relevanceJob?.id ?? null}
        jobTitle={relevanceJob?.title}
        onClose={() => setRelevanceJob(null)}
      />
      <RecommendationAuditDrawer
        jobId={auditJob?.id ?? null}
        jobTitle={auditJob?.title}
        onClose={() => setAuditJob(null)}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Recommendation card — decision actions + explain/audit drill-downs
// ---------------------------------------------------------------------------

function matchTone(score: number): BadgeTone {
  if (score >= 70) return 'success';
  if (score >= 50) return 'primary';
  return 'warning';
}

function RecommendationCard({
  rec,
  index,
  busy,
  onDecide,
  onExplain,
  onAudit,
}: {
  rec: RecommendedJob;
  index: number;
  busy: boolean;
  onDecide: (action: DecisionAction) => void;
  onExplain: () => void;
  onAudit: () => void;
}) {
  const { job, matchScore, matchedSkills, missingSkills, confidenceLevel, category } = rec;
  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: index * 0.04 }}>
      <Card className="p-5">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <h3 className="text-base font-semibold text-foreground">{job.title}</h3>
            <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
              <Building2 className="h-3.5 w-3.5" /> {job.company}
              {job.location && <span>• {job.location}</span>}
            </p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-1.5">
            <Badge tone={matchTone(matchScore)}>Match {matchScore}%</Badge>
            {confidenceLevel && <Badge tone="neutral" className="text-[10px]">{confidenceLevel} confidence</Badge>}
            {category && <Badge tone="info" className="text-[10px]">{category.replace(/_/g, ' ')}</Badge>}
          </div>
        </div>

        <JobBadges job={job} className="mt-3" priority={rec.priority} mustApply={rec.mustApply} />

        {(matchedSkills.length > 0 || missingSkills.length > 0) && (
          <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs">
            {matchedSkills.length > 0 && (
              <span className="text-success">✓ {matchedSkills.slice(0, 6).join(', ')}</span>
            )}
            {missingSkills.length > 0 && (
              <span className="text-warning">✗ {missingSkills.slice(0, 4).join(', ')}</span>
            )}
          </div>
        )}

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={() => onDecide('approve')} disabled={busy}>
            <CheckCircle2 className="h-3.5 w-3.5" /> Approve
          </Button>
          <Button size="sm" variant="outline" onClick={() => onDecide('save')} disabled={busy}>
            <Bookmark className="h-3.5 w-3.5" /> Save
          </Button>
          <Button size="sm" variant="outline" onClick={() => onDecide('reject')} disabled={busy}>
            <ThumbsDown className="h-3.5 w-3.5" /> Reject
          </Button>
          <Button size="sm" variant="ghost" onClick={() => onDecide('archive')} disabled={busy}>
            <Archive className="h-3.5 w-3.5" /> Archive
          </Button>
          <span className="ml-auto flex items-center gap-1">
            <Button size="sm" variant="ghost" onClick={onExplain}>
              <HelpCircle className="h-3.5 w-3.5" /> Explain
            </Button>
            <Button size="sm" variant="ghost" onClick={onAudit}>
              <ClipboardList className="h-3.5 w-3.5" /> Audit
            </Button>
          </span>
        </div>
      </Card>
    </motion.div>
  );
}

// ---------------------------------------------------------------------------
// Audit — full trail table + per-job score-breakdown drawer (recommendation_audit)
// ---------------------------------------------------------------------------

function useAuditRows() {
  return useQuery<RecommendationAuditRow[]>({
    queryKey: ['recommendations', 'audit-trail'],
    queryFn: async () => {
      try {
        return (await api.get('/api/recommendations/audit')).data;
      } catch {
        return [];
      }
    },
    retry: false,
    staleTime: 30_000,
  });
}

function AuditTable({ onInspect }: { onInspect: (jobId: string) => void }) {
  const { data, isLoading } = useAuditRows();
  const rows = data ?? [];

  if (isLoading) return <Skeleton className="h-64 w-full rounded-xl" />;
  if (rows.length === 0) {
    return (
      <EmptyState
        icon={ClipboardList}
        title="No audit trail yet"
        description="Every scored recommendation and decision is written to the audit ledger once the recommendation engine is enabled."
      />
    );
  }
  return (
    <Card>
      <CardContent className="pt-6">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                <th className="py-2 pr-4">Job</th>
                <th className="py-2 pr-4">Final</th>
                <th className="py-2 pr-4">Skill</th>
                <th className="py-2 pr-4">Role</th>
                <th className="py-2 pr-4">Location</th>
                <th className="py-2 pr-4">Visa</th>
                <th className="py-2 pr-4">Salary</th>
                <th className="py-2 pr-4">Source</th>
                <th className="py-2 pr-4">Decision</th>
                <th className="py-2 pr-4">When</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr
                  key={r.id}
                  className="cursor-pointer border-b border-border/50 hover:bg-muted/40"
                  onClick={() => onInspect(r.jobId)}
                >
                  <td className="py-2 pr-4 font-mono text-xs text-muted-foreground">{r.jobId.slice(0, 8)}</td>
                  <td className="py-2 pr-4"><Badge tone={matchTone(r.finalScore)}>{r.finalScore}</Badge></td>
                  <td className="py-2 pr-4 tabular-nums">{r.skillScore}</td>
                  <td className="py-2 pr-4 tabular-nums">{r.roleScore}</td>
                  <td className="py-2 pr-4 tabular-nums">{r.locationScore}</td>
                  <td className="py-2 pr-4 tabular-nums">{r.visaScore}</td>
                  <td className="py-2 pr-4 tabular-nums">{r.salaryScore}</td>
                  <td className="py-2 pr-4 text-muted-foreground">{r.profileSource}</td>
                  <td className="py-2 pr-4">
                    {r.decision ? <Badge tone="neutral">{r.decision}</Badge> : '—'}
                  </td>
                  <td className="py-2 pr-4 text-muted-foreground">{new Date(r.createdAt).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  );
}

const AUDIT_FACTORS: { key: keyof RecommendationAuditRow; label: string }[] = [
  { key: 'skillScore', label: 'Skills' },
  { key: 'roleScore', label: 'Role fit' },
  { key: 'preferenceScore', label: 'Preferences' },
  { key: 'locationScore', label: 'Location' },
  { key: 'visaScore', label: 'Visa' },
  { key: 'salaryScore', label: 'Salary' },
];

function RecommendationAuditDrawer({
  jobId,
  jobTitle,
  onClose,
}: {
  jobId: string | null;
  jobTitle?: string;
  onClose: () => void;
}) {
  const { data, isLoading } = useAuditRows();
  const rows = useMemo(
    () => (data ?? []).filter((r) => r.jobId === jobId).sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [data, jobId],
  );
  const latest = rows[0];

  return (
    <Dialog open={!!jobId} onOpenChange={(o) => !o && onClose()} size="lg">
      <DialogHeader onClose={onClose}>
        <DialogTitle>
          <span className="flex items-center gap-2">
            <ClipboardList className="h-4 w-4 text-primary" /> Recommendation audit
          </span>
        </DialogTitle>
        <DialogDescription>{jobTitle ?? 'Score breakdown and decision history for this job.'}</DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-5">
        {isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : !latest ? (
          <p className="text-sm text-muted-foreground">
            No audit rows for this job yet — the ledger populates once the recommendation engine scores it.
          </p>
        ) : (
          <>
            <div className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2.5">
              <span className="text-sm font-medium text-foreground">Final score</span>
              <div className="flex items-center gap-2">
                <Badge tone={matchTone(latest.finalScore)}>{latest.finalScore}/100</Badge>
                <Badge tone="neutral">{latest.profileSource}</Badge>
              </div>
            </div>

            <div className="space-y-2">
              {AUDIT_FACTORS.map((f) => {
                const v = Math.max(0, Math.min(100, Number(latest[f.key] ?? 0)));
                return (
                  <div key={f.key} className="flex items-center gap-3">
                    <span className="w-24 shrink-0 text-xs text-muted-foreground">{f.label}</span>
                    <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                      <div
                        className={v >= 70 ? 'h-full rounded-full bg-success' : v >= 40 ? 'h-full rounded-full bg-warning' : 'h-full rounded-full bg-danger'}
                        style={{ width: `${v}%` }}
                      />
                    </div>
                    <span className="w-8 shrink-0 text-right text-xs font-medium tabular-nums text-foreground">{v}</span>
                  </div>
                );
              })}
            </div>

            {rows.length > 0 && (
              <div>
                <h4 className="mb-2 text-sm font-semibold text-foreground">Decision timeline</h4>
                <ol className="space-y-2 border-l border-border pl-4">
                  {rows.map((r) => (
                    <li key={r.id} className="relative">
                      <span className="absolute -left-[21px] top-1 h-2 w-2 rounded-full bg-primary" />
                      <div className="flex items-center gap-2 text-sm">
                        <span className="font-medium text-foreground">Scored {r.finalScore}</span>
                        {r.decision && <Badge tone="neutral" className="text-[10px]">{r.decision}</Badge>}
                        <span className="ml-auto text-xs text-muted-foreground">
                          {new Date(r.createdAt).toLocaleString()}
                        </span>
                      </div>
                    </li>
                  ))}
                </ol>
              </div>
            )}
          </>
        )}
      </DialogBody>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// Behavior intelligence — learned preferences + feedback trail (Phase 2C Step 7)
// ---------------------------------------------------------------------------

/** Behavior-profile preference fields arrive as text (JSON array or CSV) — parse defensively. */
function parseListField(raw?: string | null): string[] {
  if (!raw || !raw.trim()) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean);
  } catch {
    /* not JSON — fall through to CSV */
  }
  return raw.split(',').map((s) => s.trim()).filter(Boolean);
}

const BEHAVIOR_ROWS: { label: string; preferred: keyof BehaviorProfile; rejected: keyof BehaviorProfile }[] = [
  { label: 'Roles', preferred: 'preferredRoles', rejected: 'rejectedRoles' },
  { label: 'Countries', preferred: 'preferredCountries', rejected: 'rejectedCountries' },
  { label: 'Work modes', preferred: 'preferredWorkModes', rejected: 'rejectedWorkModes' },
  { label: 'Domains', preferred: 'preferredDomains', rejected: 'rejectedDomains' },
];

function BehaviorIntelligencePanel() {
  const qc = useQueryClient();
  const { toast } = useToast();

  const profile = useQuery<BehaviorProfile | null>({
    queryKey: ['recommendations', 'behavior-profile'],
    queryFn: async () => {
      try {
        return (await api.get('/api/recommendations/behavior-profile')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const feedback = useQuery<RecommendationFeedbackRow[]>({
    queryKey: ['recommendations', 'feedback-history'],
    queryFn: async () => {
      try {
        return (await api.get('/api/recommendations/feedback')).data;
      } catch {
        return [];
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const rebuild = useMutation({
    mutationFn: async () => (await api.post('/api/recommendations/behavior-profile/rebuild')).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['recommendations', 'behavior-profile'] });
      toast({ variant: 'success', title: 'Behavior profile rebuilt', description: 'Recomputed from your feedback history.' });
    },
    onError: () => toast({ variant: 'error', title: 'Rebuild failed', description: 'Recommendation feedback may not be enabled yet.' }),
  });

  const p = profile.data;
  const feedbackRows = feedback.data ?? [];

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="flex items-center gap-2 text-base">
            <Brain className="h-4 w-4 text-muted-foreground" /> Learned preferences
          </CardTitle>
          <Button size="sm" variant="outline" onClick={() => rebuild.mutate()} loading={rebuild.isPending}>
            <RefreshCw className="h-3.5 w-3.5" /> Rebuild
          </Button>
        </CardHeader>
        <CardContent>
          {profile.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : !p ? (
            <p className="text-sm text-muted-foreground">
              No behavior profile yet — it's inferred from your approve/reject/save feedback once the
              feedback engine is enabled.
            </p>
          ) : (
            <div className="space-y-4">
              {BEHAVIOR_ROWS.map(({ label, preferred, rejected }) => {
                const pos = parseListField(p[preferred] as string | null | undefined);
                const neg = parseListField(p[rejected] as string | null | undefined);
                if (pos.length === 0 && neg.length === 0) return null;
                return (
                  <div key={label}>
                    <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{label}</p>
                    <div className="flex flex-wrap gap-1.5">
                      {pos.map((v) => (
                        <Badge key={`p-${v}`} tone="success">✓ {v}</Badge>
                      ))}
                      {neg.map((v) => (
                        <Badge key={`n-${v}`} tone="danger">✗ {v}</Badge>
                      ))}
                    </div>
                  </div>
                );
              })}
              {p.updatedAt && (
                <p className="text-xs text-muted-foreground">Last learned: {new Date(p.updatedAt).toLocaleString()}</p>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Sparkles className="h-4 w-4 text-muted-foreground" /> Feedback history
          </CardTitle>
        </CardHeader>
        <CardContent>
          {feedback.isLoading ? (
            <Skeleton className="h-40 w-full" />
          ) : feedbackRows.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No feedback recorded yet — approving, rejecting, or saving a recommendation writes a learning signal here.
            </p>
          ) : (
            <div className="max-h-80 space-y-2 overflow-y-auto pr-1">
              {feedbackRows.map((f) => (
                <div key={f.id} className="flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm">
                  <Badge
                    tone={f.action === 'APPROVE' ? 'success' : f.action === 'REJECT' ? 'danger' : 'neutral'}
                    className="text-[10px]"
                  >
                    {f.action}
                  </Badge>
                  <span className="font-mono text-xs text-muted-foreground">{f.jobId.slice(0, 8)}</span>
                  {f.reason && <span className="truncate text-xs text-muted-foreground">“{f.reason}”</span>}
                  <span className="ml-auto shrink-0 text-xs text-muted-foreground">
                    {new Date(f.createdAt).toLocaleDateString()}
                  </span>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
