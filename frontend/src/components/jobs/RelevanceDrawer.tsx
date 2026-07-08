import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, HelpCircle, Sparkles, XCircle } from 'lucide-react';
import { api } from '@/lib/api';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs } from '@/components/ui/tabs';
import { Dialog, DialogBody, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import type { CareerIntelligenceRow, CareerLearningView, JobRelevance, RecommendedJob } from '@/types/workflow';

type RelevanceTab = 'summary' | 'discovery' | 'analysis' | 'recommendation' | 'reasons' | 'career' | 'learning';

interface RelevanceDrawerProps {
  jobId: string | null;
  jobTitle?: string;
  onClose: () => void;
  /**
   * Phase 5.1B — Daily Discovery Drawer: when the caller has a scored recommendation on hand
   * (from the Recommended tab), pass it to unlock the Discovery/Recommendation/Career
   * Intelligence tabs using data already fetched — no extra network calls beyond the existing
   * relevance + career-intelligence endpoints.
   */
  rec?: RecommendedJob | null;
}

function pct(v?: number | null): string {
  return v == null ? '—' : `${Math.round(v * 100)}%`;
}

function strengthTone(strength: string): 'success' | 'primary' | 'warning' | 'danger' {
  const s = strength.toLowerCase();
  if (s.includes('excellent') || s.includes('strong')) return 'success';
  if (s.includes('good')) return 'primary';
  if (s.includes('weak')) return 'warning';
  return 'danger';
}

function Check({ ok, label }: { ok: boolean; label: string }) {
  return (
    <li className="flex items-center gap-2 text-sm">
      {ok ? (
        <CheckCircle2 className="h-4 w-4 shrink-0 text-success" />
      ) : (
        <XCircle className="h-4 w-4 shrink-0 text-muted-foreground" />
      )}
      <span className={ok ? 'text-foreground' : 'text-muted-foreground'}>{label}</span>
    </li>
  );
}

/**
 * Phase 4.4 — "Why am I seeing this?" drawer. Consumes GET /api/jobs/{id}/relevance
 * (Phase 3B.1 explainability). The endpoint 404s when career.explainability.enabled is
 * false, so a failed fetch renders a quiet "not available yet" state rather than an error.
 */
export function RelevanceDrawer({ jobId, jobTitle, onClose, rec }: RelevanceDrawerProps) {
  const [tab, setTab] = useState<RelevanceTab>('summary');
  useEffect(() => setTab('summary'), [jobId]);
  const { data, isLoading } = useQuery<JobRelevance | null>({
    queryKey: ['jobs', 'relevance', jobId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/jobs/${jobId}/relevance`)).data as JobRelevance;
      } catch {
        return null;
      }
    },
    enabled: !!jobId,
    retry: false,
    staleTime: 60_000,
  });

  const career = useQuery<CareerIntelligenceRow[]>({
    queryKey: ['jobs', 'relevance-career-intel'],
    queryFn: async () => {
      try {
        return (await api.get('/api/workflow/career-intelligence')).data;
      } catch {
        return [];
      }
    },
    enabled: !!jobId && tab === 'career',
    retry: false,
    staleTime: 60_000,
  });

  const learning = useQuery<CareerLearningView | null>({
    queryKey: ['jobs', 'relevance-career-learning'],
    queryFn: async () => {
      try {
        return (await api.get('/api/workflow/career-learning')).data as CareerLearningView;
      } catch {
        return null;
      }
    },
    enabled: !!jobId && tab === 'learning',
    retry: false,
    staleTime: 60_000,
  });

  const job = rec?.job;
  const discoveryDate = job?.postedDate ?? job?.createdAt;

  return (
    <Dialog open={!!jobId} onOpenChange={(o) => !o && onClose()} size="md">
      <DialogHeader onClose={onClose}>
        <DialogTitle>
          <span className="flex items-center gap-2">
            <HelpCircle className="h-4 w-4 text-primary" /> Why am I seeing this?
          </span>
        </DialogTitle>
        <DialogDescription>{jobTitle ?? 'Career relevance breakdown for this role.'}</DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-4">
        {isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-6 w-full rounded-md" />
            ))}
          </div>
        ) : (
          <>
            <Tabs
              items={[
                { value: 'summary', label: 'Summary' },
                ...(rec ? [{ value: 'discovery', label: 'Discovery' }] : []),
                { value: 'analysis', label: 'Matching' },
                ...(rec ? [{ value: 'recommendation', label: 'Recommendation' }] : []),
                { value: 'reasons', label: 'Relevance', count: data?.reasons.length },
                ...(rec ? [{ value: 'career', label: 'Career Intelligence' }] : []),
                ...(rec ? [{ value: 'learning', label: 'Learning' }] : []),
              ]}
              value={tab}
              onChange={(v) => setTab(v as RelevanceTab)}
            />

            {tab === 'summary' && (
              data ? (
                <div className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                  <span className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    <Sparkles className="h-4 w-4 text-primary" /> Relevance score
                  </span>
                  <div className="flex items-center gap-2">
                    <span className="tabular-nums text-sm font-semibold text-foreground">{data.relevanceScore}%</span>
                    <Badge tone={strengthTone(data.matchStrength)}>{data.matchStrength}</Badge>
                  </div>
                </div>
              ) : rec ? (
                <div className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                  <span className="text-sm font-medium text-foreground">Match score</span>
                  <Badge tone="primary">{rec.matchScore}%</Badge>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">Career relevance explainability isn't available for this role yet.</p>
              )
            )}

            {tab === 'discovery' && rec && job && (
              <ul className="space-y-2 rounded-lg border border-border p-3 text-sm">
                <li className="flex justify-between"><span className="text-muted-foreground">Source</span><span className="font-medium text-foreground">{job.source ?? '—'}</span></li>
                <li className="flex justify-between"><span className="text-muted-foreground">Discovery date</span><span className="font-medium text-foreground">{discoveryDate ? new Date(discoveryDate).toLocaleDateString() : '—'}</span></li>
                <li className="flex justify-between"><span className="text-muted-foreground">Location</span><span className="font-medium text-foreground">{job.location ?? job.country ?? '—'}</span></li>
                <li className="flex justify-between"><span className="text-muted-foreground">Remote type</span><span className="font-medium text-foreground">{job.remoteType ?? (job.remote ? 'REMOTE' : '—')}</span></li>
              </ul>
            )}

            {tab === 'analysis' && (
              data ? (
                <ul className="space-y-2 rounded-lg border border-border p-3">
                  <Check ok={data.roleMatch} label="Role match" />
                  <Check ok={data.skillOverlap > 0} label={`Skill overlap (${data.skillOverlap} matched)`} />
                  <Check ok={data.experienceFit} label="Experience fit" />
                  <Check ok={data.domainFit} label="Domain match" />
                </ul>
              ) : rec ? (
                <div className="space-y-2 rounded-lg border border-border p-3 text-sm">
                  {rec.matchedSkills.length > 0 && (
                    <p><span className="text-muted-foreground">Matched skills: </span>{rec.matchedSkills.join(', ')}</p>
                  )}
                  {rec.missingSkills.length > 0 && (
                    <p><span className="text-muted-foreground">Missing skills: </span>{rec.missingSkills.join(', ')}</p>
                  )}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">Not available for this role yet.</p>
              )
            )}

            {tab === 'recommendation' && rec && (
              <ul className="space-y-2 rounded-lg border border-border p-3 text-sm">
                <li className="flex justify-between"><span className="text-muted-foreground">Category</span><span className="font-medium text-foreground">{rec.category?.replaceAll('_', ' ') ?? '—'}</span></li>
                <li className="flex justify-between"><span className="text-muted-foreground">Priority</span><span className="font-medium text-foreground">{rec.priority ?? '—'}</span></li>
                <li className="flex justify-between"><span className="text-muted-foreground">Must apply</span><span className="font-medium text-foreground">{rec.mustApply ? 'Yes' : 'No'}</span></li>
                <li className="flex justify-between"><span className="text-muted-foreground">Confidence</span><span className="font-medium text-foreground">{rec.confidenceLevel ?? '—'}</span></li>
              </ul>
            )}

            {tab === 'career' && (
              career.isLoading ? (
                <Skeleton className="h-20 w-full" />
              ) : (career.data ?? []).filter((r) => typeof r.probability === 'number').length === 0 ? (
                <p className="text-sm text-muted-foreground">Career intelligence isn't enabled yet.</p>
              ) : (
                <ul className="space-y-1.5">
                  {(career.data ?? [])
                    .filter((r) => typeof r.probability === 'number')
                    .slice(0, 5)
                    .map((r, i) => (
                      <li key={i} className="flex items-center justify-between text-sm">
                        <span className="text-muted-foreground">{r.dimensionKey || r.dimension}</span>
                        <span className="font-semibold tabular-nums text-foreground">{Math.round((r.probability ?? 0) * 100)}%</span>
                      </li>
                    ))}
                </ul>
              )
            )}

            {tab === 'learning' && (
              learning.isLoading ? (
                <Skeleton className="h-20 w-full" />
              ) : !learning.data || (!learning.data.strategy && learning.data.topCompanies.length === 0
                  && learning.data.topSkills.length === 0) ? (
                <p className="text-sm text-muted-foreground">
                  Learning-based insights aren't enabled yet — they build up from your application history.
                </p>
              ) : (
                <div className="space-y-3">
                  {rec?.scoreBreakdown?.learningBoost != null && rec.scoreBreakdown.learningBoost !== 0 && (
                    <div className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2.5 text-sm">
                      <span className="text-muted-foreground">Learning boost on this job</span>
                      <span className="font-semibold tabular-nums text-foreground">
                        {rec.scoreBreakdown.learningBoost > 0 ? '+' : ''}{rec.scoreBreakdown.learningBoost}
                      </span>
                    </div>
                  )}
                  {learning.data.strategy && (
                    <ul className="space-y-1.5 rounded-lg border border-border p-3 text-sm">
                      <li className="flex justify-between"><span className="text-muted-foreground">Career success probability</span><span className="font-medium text-foreground">{pct(learning.data.strategy.careerSuccessProbability)}</span></li>
                      <li className="flex justify-between"><span className="text-muted-foreground">Offer probability</span><span className="font-medium text-foreground">{pct(learning.data.strategy.offerProbability)}</span></li>
                      <li className="flex justify-between"><span className="text-muted-foreground">Interview probability</span><span className="font-medium text-foreground">{pct(learning.data.strategy.interviewProbability)}</span></li>
                    </ul>
                  )}
                  {learning.data.topSkills.length > 0 && (
                    <div>
                      <p className="mb-1 text-xs font-medium text-muted-foreground">Your historically-successful skills</p>
                      <p className="text-sm text-foreground">
                        {learning.data.topSkills.slice(0, 5).map((s) => s.dimensionKey).filter(Boolean).join(', ')}
                      </p>
                    </div>
                  )}
                  {learning.data.topCompanies.length > 0 && (
                    <div>
                      <p className="mb-1 text-xs font-medium text-muted-foreground">Your historically-successful companies</p>
                      <p className="text-sm text-foreground">
                        {learning.data.topCompanies.slice(0, 5).map((c) => c.dimensionKey).filter(Boolean).join(', ')}
                      </p>
                    </div>
                  )}
                </div>
              )
            )}

            {tab === 'reasons' && (
              data && data.reasons.length > 0 ? (
                <ul className="space-y-1.5">
                  {data.reasons.map((r, i) => (
                    <li key={i} className="flex gap-2 text-sm text-muted-foreground">
                      <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-muted-foreground" />
                      {r}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-sm text-muted-foreground">No specific reasons recorded.</p>
              )
            )}
          </>
        )}
      </DialogBody>
    </Dialog>
  );
}
