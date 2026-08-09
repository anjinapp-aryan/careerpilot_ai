import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Bot,
  Building2,
  Camera,
  ClipboardCheck,
  Download,
  ExternalLink,
  FileJson,
  Info,
  ListTree,
  MapPin,
  PackageCheck,
  Pause,
  RotateCcw,
  Save,
  Sparkles,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { Drawer, DrawerHeader, DrawerBody, DrawerTitle, DrawerDescription } from '@/components/ui/drawer';
import { Tabs } from '@/components/ui/tabs';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/input';
import { useToast } from '@/components/ui/toast';
import { CompanyIntelPanel } from '@/components/company/CompanyIntelPanel';
import { ApplicationPackageDrawer } from '@/components/applications/ApplicationPackageDrawer';
import { ApplicationReviewDrawer } from '@/components/applications/ApplicationReviewDrawer';
import { generatePackage } from '@/lib/applicationPackage';
import { runReview } from '@/lib/applicationReview';
import { ApplicationHealthBadge } from './ApplicationHealthBadge';
import { ApplicationTimelineStepper } from './ApplicationTimelineStepper';
import { AiRecommendationPanel } from './AiRecommendationPanel';
import { NextActionCard } from './NextActionCard';
import { InterviewReadinessPanel } from './InterviewReadinessPanel';
import { WorkflowStageChecklist } from './WorkflowStageChecklist';
import { GuidedApplyBriefPanel } from './GuidedApplyBriefPanel';
import { ExecutionEvidencePanel } from './ExecutionEvidencePanel';
import type { ApplicationCard, LifecycleView, TimelineEntry } from '@/types/workflow';

const TABS = [
  { value: 'overview', label: 'Overview' },
  { value: 'guidedApply', label: 'Guided Apply' },
  { value: 'timeline', label: 'Timeline' },
  { value: 'resume', label: 'Resume' },
  { value: 'coverLetter', label: 'Cover Letter' },
  { value: 'interview', label: 'Interview' },
  { value: 'notes', label: 'Notes' },
  { value: 'documents', label: 'Documents' },
  { value: 'aiInsights', label: 'AI Insights' },
  { value: 'automation', label: 'Automation' },
  { value: 'history', label: 'History' },
];

const AUTOMATION_HEALTH_TONE: Record<string, 'success' | 'warning' | 'danger' | 'neutral' | 'primary'> = {
  RUNNING: 'primary', WAITING: 'warning', RETRYING: 'warning', MANUAL_REVIEW: 'danger',
  RECOVERED: 'success', COMPLETED: 'success', VERIFICATION_FAILED: 'danger', FAILED: 'danger',
  GUIDED_APPLY_REQUIRED: 'warning',
};

const SUGGESTED_PROMPTS = [
  'Why is this application stuck?',
  'How do I improve my chances?',
  'Should I follow up?',
  'Generate a follow-up email',
  'Predict my interview chance',
  'Generate an outreach email',
  'Prepare for interview',
  'Compare against job description',
];

/**
 * Applications Command Center — the new true slide-in Drawer, replacing the old centered-modal
 * `ApplicationDetailDialog`. Same trigger (card click), richer 9-tab content. Reads the new
 * `GET /api/applications/{id}` card endpoint plus the untouched Phase 3A lifecycle/timeline reads,
 * so it degrades gracefully to "not tracked yet" exactly like the dialog it replaces.
 */
export function ApplicationDrawer({
  applicationId,
  onClose,
}: {
  applicationId: string | null;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [tab, setTab] = useState('overview');
  const [notes, setNotes] = useState('');
  const [packageId, setPackageId] = useState<string | null>(null);
  const [reviewPackageId, setReviewPackageId] = useState<string | null>(null);

  useEffect(() => {
    setTab('overview');
    setPackageId(null);
    setReviewPackageId(null);
  }, [applicationId]);

  const cardQuery = useQuery<ApplicationCard | null>({
    queryKey: ['applications', 'card', applicationId],
    queryFn: async () => (await api.get(`/api/applications/${applicationId}`)).data,
    enabled: !!applicationId,
    retry: false,
  });
  const card = cardQuery.data ?? null;

  // Guided Apply — jump straight to the Guided Apply tab when an application needs it, since it's
  // the most important thing for the user to see (Section 16's information hierarchy).
  useEffect(() => {
    if (card?.guidedApplyRequired) setTab('guidedApply');
  }, [applicationId, card?.guidedApplyRequired]);

  const tabItems = card?.guidedApplyRequired ? TABS : TABS.filter((t) => t.value !== 'guidedApply');

  useEffect(() => {
    setNotes(card?.notes ?? '');
  }, [card?.notes, applicationId]);

  const lifecycle = useQuery<LifecycleView | null>({
    queryKey: ['workflow', 'lifecycle', card?.jobId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/workflow/applications/${card?.jobId}/lifecycle`)).data as LifecycleView;
      } catch {
        return null;
      }
    },
    enabled: !!card?.jobId,
    retry: false,
  });

  const timeline = useQuery<TimelineEntry[]>({
    queryKey: ['workflow', 'timeline', card?.jobId],
    queryFn: async () => (await api.get(`/api/workflow/applications/${card?.jobId}/timeline`)).data,
    enabled: !!card?.jobId,
    retry: false,
  });

  const saveNotes = useMutation({
    mutationFn: async () => (await api.patch(`/api/applications/${applicationId}`, { notes })).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'Notes saved' });
      qc.invalidateQueries({ queryKey: ['applications'] });
      qc.invalidateQueries({ queryKey: ['applications', 'card', applicationId] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not save notes' }),
  });

  const genPackage = useMutation({
    mutationFn: () => generatePackage(card!.jobId),
    onSuccess: (pkg) => setPackageId(pkg.id),
    onError: () =>
      toast({ variant: 'default', title: 'Application package not available', description: 'Enable application.package.validation.enabled to assemble packages.' }),
  });

  const runReviewMut = useMutation({
    mutationFn: async () => {
      const pkg = await generatePackage(card!.jobId);
      await runReview(pkg.id);
      return pkg.id;
    },
    onSuccess: (id) => setReviewPackageId(id),
    onError: () =>
      toast({ variant: 'default', title: 'AI review not available', description: 'Enable application.review.enabled to run reviews.' }),
  });

  // ── Phase 7.16.4 — Application Detail Workspace (Automation tab) ──
  const executionId = card?.executionId ?? null;
  const executionDetail = useQuery({
    queryKey: ['execution', 'detail', executionId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/execution/executions/${executionId}/detail`)).data;
      } catch {
        return null;
      }
    },
    enabled: tab === 'automation' && !!executionId,
    retry: false,
  });
  const executionExplain = useQuery({
    queryKey: ['execution', 'explain', executionId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/execution/executions/${executionId}/explain`)).data;
      } catch {
        return null;
      }
    },
    enabled: tab === 'automation' && !!executionId,
    retry: false,
  });

  const retryNowMut = useMutation({
    mutationFn: async () => (await api.post(`/api/execution/executions/${executionId}/retry-now`)).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'Retry started' });
      qc.invalidateQueries({ queryKey: ['execution'] });
      qc.invalidateQueries({ queryKey: ['applications'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not retry', description: 'The execution may not be in a retryable state.' }),
  });
  const cancelExecutionMut = useMutation({
    mutationFn: async () => (await api.post(`/api/execution/executions/${executionId}/cancel`, { reason: 'cancelled from Application Detail Workspace' })).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'Execution cancelled' });
      qc.invalidateQueries({ queryKey: ['execution'] });
      qc.invalidateQueries({ queryKey: ['applications'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not cancel' }),
  });
  const manualReviewMut = useMutation({
    mutationFn: async () => (await api.post(`/api/execution/executions/${executionId}/manual-review`, { reason: 'requested from Application Detail Workspace' })).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'Manual review requested' });
      qc.invalidateQueries({ queryKey: ['execution'] });
      qc.invalidateQueries({ queryKey: ['applications'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not request manual review' }),
  });

  const downloadJson = async (path: string, filename: string) => {
    try {
      const res = await api.get(path);
      const blob = new Blob([JSON.stringify(res.data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      toast({ variant: 'error', title: 'Download not available' });
    }
  };

  return (
    <Drawer open={!!applicationId} onOpenChange={(o) => !o && onClose()} size="xl">
      <DrawerHeader onClose={onClose}>
        <DrawerTitle>
          <span className="flex items-center gap-2">
            {card?.jobTitle ?? 'Application'}
            {card && <ApplicationHealthBadge status={card.healthStatus} />}
          </span>
        </DrawerTitle>
        <DrawerDescription>
          {card && (
            <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="flex items-center gap-1"><Building2 className="h-3 w-3" /> {card.company}</span>
              {card.location && <span className="flex items-center gap-1"><MapPin className="h-3 w-3" /> {card.location}</span>}
              {card.externalUrl && (
                <a href={card.externalUrl} target="_blank" rel="noreferrer" className="flex items-center gap-1 text-primary hover:underline">
                  <ExternalLink className="h-3 w-3" /> Listing
                </a>
              )}
            </span>
          )}
        </DrawerDescription>
      </DrawerHeader>
      <Tabs items={tabItems} value={tab} onChange={setTab} className="px-6" />
      <DrawerBody className="space-y-4">
        {cardQuery.isLoading || !card ? (
          <div className="space-y-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-20 rounded-lg" />
            ))}
          </div>
        ) : (
          <>
            {tab === 'overview' && (
              <div className="space-y-4">
                {card.guidedApplyRequired && (
                  <button
                    type="button"
                    onClick={() => setTab('guidedApply')}
                    className="flex w-full items-center justify-between gap-3 rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 text-left"
                  >
                    <span className="text-sm font-medium text-foreground">
                      🟡 Guided Apply — CareerPilot prepared your application; you'll need to complete it manually.
                    </span>
                    <Badge tone="warning">View brief</Badge>
                  </button>
                )}
                <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                  <NextActionCard card={card} />
                  <AiRecommendationPanel card={card} />
                </div>
                <dl className="grid grid-cols-2 gap-x-6 gap-y-2 rounded-lg border border-border bg-muted/20 p-3 text-xs text-muted-foreground">
                  <div><dt className="font-medium text-foreground">Status</dt><dd>{card.status}</dd></div>
                  <div><dt className="font-medium text-foreground">Salary</dt><dd>{card.salaryRange ?? '—'}</dd></div>
                  <div><dt className="font-medium text-foreground">Remote</dt><dd>{card.remoteType ?? '—'}</dd></div>
                  <div><dt className="font-medium text-foreground">Sponsorship</dt><dd>{card.sponsorshipAvailable == null ? '—' : card.sponsorshipAvailable ? 'Yes' : 'No'}</dd></div>
                  <div><dt className="font-medium text-foreground">Visa required</dt><dd>{card.visaRequired == null ? '—' : card.visaRequired ? 'Yes' : 'No'}</dd></div>
                  <div><dt className="font-medium text-foreground">Source</dt><dd>{card.source ?? '—'}</dd></div>
                </dl>
                <div>
                  <h4 className="mb-2 text-sm font-semibold text-foreground">Workflow pipeline</h4>
                  <WorkflowStageChecklist card={card} onNavigate={setTab} />
                </div>
                <CompanyIntelPanel companyName={card.company} jobId={card.jobId} />
              </div>
            )}

            {tab === 'guidedApply' && card.guidedApplyRequired && (
              <GuidedApplyBriefPanel applicationId={card.id} card={card} />
            )}

            {tab === 'timeline' && (
              <div className="space-y-4">
                <ApplicationTimelineStepper lifecycle={lifecycle.data} />
                {lifecycle.isLoading || timeline.isLoading ? (
                  <Skeleton className="h-24 rounded-lg" />
                ) : (
                  <>
                    {(lifecycle.data?.history ?? []).length > 0 && (
                      <div>
                        <h4 className="mb-2 text-sm font-semibold text-foreground">Status history</h4>
                        <div className="space-y-1.5">
                          {lifecycle.data!.history!.map((h, i) => (
                            <div key={i} className="flex items-center gap-2 text-xs text-muted-foreground">
                              <span className="tabular-nums">{h.fromStatus ?? '—'} → <span className="font-medium text-foreground">{h.toStatus ?? '—'}</span></span>
                              {h.createdAt && <span className="ml-auto">{new Date(h.createdAt).toLocaleString()}</span>}
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                    {(timeline.data ?? []).length === 0 && (lifecycle.data?.history ?? []).length === 0 && (
                      <p className="rounded-lg border border-dashed border-border bg-muted/20 p-4 text-sm text-muted-foreground">
                        <Info className="mb-1 h-4 w-4" /> No workflow tracking events recorded yet for this application.
                      </p>
                    )}
                  </>
                )}
              </div>
            )}

            {tab === 'resume' && (
              <div className="space-y-3 text-sm">
                <StatusRow label="Resume tailored" ok={card.resumeTailored} />
                <StatusRow label="ATS analysis available" ok={card.atsAnalysisReady} />
                {card.atsScore != null && <p className="text-muted-foreground">Current ATS score: <span className="font-medium text-foreground">{card.atsScore}</span></p>}
                {card.matchScore != null && <p className="text-muted-foreground">Job match score: <span className="font-medium text-foreground">{card.matchScore}</span></p>}
              </div>
            )}

            {tab === 'coverLetter' && (
              <div className="space-y-3 text-sm">
                <StatusRow label="Cover letter generated" ok={card.coverLetterReady} />
                {!card.coverLetterReady && (
                  <p className="text-muted-foreground">
                    No cover letter yet for this application. It's generated automatically once the resume-tailoring
                    pipeline reaches that stage (Phase 2D.5), if enabled.
                  </p>
                )}
              </div>
            )}

            {tab === 'interview' && <InterviewReadinessPanel card={card} />}

            {tab === 'notes' && (
              <div className="space-y-2">
                <Textarea rows={8} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Add private notes about this application…" />
                <Button size="sm" onClick={() => saveNotes.mutate()} loading={saveNotes.isPending}>
                  <Save className="h-3.5 w-3.5" /> Save notes
                </Button>
              </div>
            )}

            {tab === 'documents' && (
              <div className="space-y-4">
                <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/20 p-3">
                  <p className="text-xs text-muted-foreground">
                    View the validated Application Package (resume, ATS, recommendation, company, learning + validation verdict).
                  </p>
                  <div className="flex shrink-0 items-center gap-2">
                    <Button size="sm" variant="outline" onClick={() => genPackage.mutate()} loading={genPackage.isPending}>
                      <PackageCheck className="h-3.5 w-3.5" /> Application package
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => runReviewMut.mutate()} loading={runReviewMut.isPending}>
                      <ClipboardCheck className="h-3.5 w-3.5" /> AI review
                    </Button>
                  </div>
                </div>
                <StatusRow label="Application package assembled" ok={card.applicationPackageReady} />
                <StatusRow label="AI review completed" ok={card.applicationReviewReady} />
              </div>
            )}

            {tab === 'aiInsights' && (
              <div className="space-y-4">
                <AiRecommendationPanel card={card} />
                <NextActionCard card={card} />
                <div>
                  <h4 className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-foreground">
                    <Sparkles className="h-4 w-4 text-primary" /> Ask Copilot
                  </h4>
                  <div className="flex flex-wrap gap-1.5">
                    {SUGGESTED_PROMPTS.map((p) => (
                      <Badge key={p} tone="neutral" className="cursor-default">{p}</Badge>
                    ))}
                  </div>
                  <p className="mt-2 text-xs text-muted-foreground">
                    Open the Copilot panel and ask any of the above — it has this application's health,
                    recommendation, and pipeline data in context.
                  </p>
                </div>
              </div>
            )}

            {tab === 'automation' && (
              <div className="space-y-4">
                {/* P7 Action 7 — always available regardless of application.operations.enabled;
                    the richer detail/explain/evidence-download blocks below stay ops-flag-gated. */}
                <ExecutionEvidencePanel applicationId={card.id} />

                {!executionId ? null : executionDetail.isLoading ? (
                  <Skeleton className="h-40 rounded-lg" />
                ) : !executionDetail.data ? (
                  <p className="rounded-lg border border-dashed border-border bg-muted/20 p-4 text-sm text-muted-foreground">
                    Automation detail isn't available — the Operations Center may not be enabled (application.operations.enabled).
                  </p>
                ) : (
                  <>
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={AUTOMATION_HEALTH_TONE[card.automationHealth ?? ''] ?? 'neutral'}>
                        {card.automationHealth ?? card.executionStatus ?? 'UNKNOWN'}
                      </Badge>
                      {card.verificationStatus && <Badge tone="neutral">Verification: {card.verificationStatus}</Badge>}
                      {typeof card.retryCount === 'number' && card.retryCount > 0 && (
                        <Badge tone="warning">{card.retryCount} retry attempt{card.retryCount === 1 ? '' : 's'} logged</Badge>
                      )}
                    </div>

                    <div className="flex flex-wrap gap-2">
                      <Button size="sm" variant="outline" onClick={() => retryNowMut.mutate()} loading={retryNowMut.isPending}>
                        <RotateCcw className="h-3.5 w-3.5" /> Retry now
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => manualReviewMut.mutate()} loading={manualReviewMut.isPending}>
                        <Pause className="h-3.5 w-3.5" /> Request manual review
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => cancelExecutionMut.mutate()} loading={cancelExecutionMut.isPending}>
                        <XCircle className="h-3.5 w-3.5" /> Cancel
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => downloadJson(`/api/execution/executions/${executionId}/evidence`, `evidence-${executionId}.json`)}>
                        <Download className="h-3.5 w-3.5" /> Evidence (JSON)
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => downloadJson(`/api/execution/executions/${executionId}/timeline-export`, `timeline-${executionId}.json`)}>
                        <FileJson className="h-3.5 w-3.5" /> Timeline (JSON)
                      </Button>
                    </div>

                    <div className="rounded-lg border border-border bg-muted/20 p-3">
                      <h4 className="mb-2 text-sm font-semibold text-foreground">Evidence</h4>
                      <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-xs text-muted-foreground">
                        <div><dt className="font-medium text-foreground">Confirmation number</dt><dd>{executionDetail.data.evidence?.confirmationNumber ?? '—'}</dd></div>
                        <div><dt className="font-medium text-foreground">Verification method</dt><dd>{executionDetail.data.evidence?.verificationMethod ?? '—'}</dd></div>
                        <div><dt className="font-medium text-foreground">Verified at</dt><dd>{executionDetail.data.evidence?.verifiedAt ? new Date(executionDetail.data.evidence.verifiedAt).toLocaleString() : '—'}</dd></div>
                        <div><dt className="font-medium text-foreground">Evidence age</dt><dd>{executionDetail.data.evidence?.evidenceAgeMs != null ? `${Math.round(executionDetail.data.evidence.evidenceAgeMs / 1000)}s` : '—'}</dd></div>
                      </dl>
                    </div>

                    {(executionDetail.data.screenshots ?? []).length > 0 && (
                      <div>
                        <h4 className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-foreground">
                          <Camera className="h-4 w-4" /> Screenshot timeline
                        </h4>
                        <div className="space-y-1.5">
                          {executionDetail.data.screenshots.map((s: { id: string; phase?: string; capturedAt?: string }) => (
                            <div key={s.id} className="flex items-center justify-between rounded-lg border border-border px-3 py-2 text-xs">
                              <Badge tone="neutral">{s.phase ?? 'UNKNOWN'}</Badge>
                              <span className="text-muted-foreground">{s.capturedAt ? new Date(s.capturedAt).toLocaleString() : '—'}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    {(executionDetail.data.retryHistory ?? []).length > 0 && (
                      <div>
                        <h4 className="mb-2 text-sm font-semibold text-foreground">Recovery / retry history</h4>
                        <div className="space-y-1.5">
                          {executionDetail.data.retryHistory.map((r: { attempt: number; failureClass: string; action: string; backoffMs?: number; createdAt?: string }, i: number) => (
                            <div key={i} className="flex items-center gap-2 text-xs text-muted-foreground">
                              <span className="font-medium text-foreground">Attempt {r.attempt}</span>
                              <Badge tone="neutral">{r.failureClass}</Badge>
                              <Badge tone={r.action === 'STOP' ? 'danger' : r.action === 'PAUSE' ? 'warning' : 'info'}>{r.action}</Badge>
                              {r.createdAt && <span className="ml-auto">{new Date(r.createdAt).toLocaleString()}</span>}
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    {executionExplain.data && (
                      <div className="rounded-lg border border-border bg-muted/20 p-3">
                        <h4 className="mb-2 text-sm font-semibold text-foreground">Explainability</h4>
                        <dl className="space-y-1.5 text-xs">
                          {[
                            ['Why retried?', executionExplain.data.whyRetried],
                            ['Why paused?', executionExplain.data.whyPaused],
                            ['Why cancelled?', executionExplain.data.whyCancelled],
                            ['Why verification failed?', executionExplain.data.whyVerificationFailed],
                            ['Why recovery succeeded?', executionExplain.data.whyRecoverySucceeded],
                            ['Why manual review required?', executionExplain.data.whyManualReviewRequired],
                          ]
                            .filter(([, v]) => v)
                            .map(([q, a]) => (
                              <div key={q as string}>
                                <dt className="font-medium text-foreground">{q}</dt>
                                <dd className="text-muted-foreground">{a as string}</dd>
                              </div>
                            ))}
                          {[
                            executionExplain.data.whyRetried, executionExplain.data.whyPaused, executionExplain.data.whyCancelled,
                            executionExplain.data.whyVerificationFailed, executionExplain.data.whyRecoverySucceeded,
                            executionExplain.data.whyManualReviewRequired,
                          ].every((v) => !v) && (
                            <p className="text-muted-foreground">Nothing to explain yet — no retry, pause, cancellation, or verification failure has been recorded.</p>
                          )}
                        </dl>
                      </div>
                    )}
                  </>
                )}
              </div>
            )}

            {tab === 'history' && (
              <div className="space-y-2">
                {(timeline.data ?? []).length === 0 ? (
                  <p className="text-sm text-muted-foreground">No event history recorded yet.</p>
                ) : (
                  <ol className="space-y-2.5 border-l border-border pl-4">
                    {timeline.data!.map((e) => (
                      <li key={e.id} className="relative">
                        <span className="absolute -left-[21px] top-1 h-2 w-2 rounded-full bg-primary" />
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-medium text-foreground">{e.eventType}</span>
                          {e.eventSource && <Badge tone="neutral" className="text-[10px]">{e.eventSource}</Badge>}
                        </div>
                        {e.details && <p className="text-xs text-muted-foreground">{e.details}</p>}
                        {e.occurredAt && <p className="text-[11px] text-muted-foreground">{new Date(e.occurredAt).toLocaleString()}</p>}
                      </li>
                    ))}
                  </ol>
                )}
              </div>
            )}
          </>
        )}
      </DrawerBody>

      <ApplicationPackageDrawer packageId={packageId} open={!!packageId} onOpenChange={(o) => !o && setPackageId(null)} />
      <ApplicationReviewDrawer packageId={reviewPackageId} open={!!reviewPackageId} onOpenChange={(o) => !o && setReviewPackageId(null)} />
    </Drawer>
  );
}

function StatusRow({ label, ok }: { label: string; ok: boolean }) {
  return (
    <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2">
      <span className="text-foreground">{label}</span>
      <Badge tone={ok ? 'success' : 'neutral'}>{ok ? 'Ready' : 'Not yet'}</Badge>
    </div>
  );
}
