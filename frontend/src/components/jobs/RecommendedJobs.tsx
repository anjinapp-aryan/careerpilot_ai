import { useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import { Link } from 'react-router-dom';
import {
  Bookmark,
  Briefcase,
  Building2,
  CheckCircle2,
  HelpCircle,
  Send,
  Sparkles,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { cn } from '@/lib/cn';
import { ExplainDialog } from '@/components/jobs/ExplainDialog';
import { JobBadges } from '@/components/jobs/JobBadges';
import { trackJobEvent } from '@/lib/jobTelemetry';
import type { RecommendedFilter, RecommendedJob, RecommendedJobsResponse } from '@/types/workflow';

const PAGE_SIZE = 10;

interface RecommendedJobsProps {
  onApply: (jobId: string) => void;
  onSave: (jobId: string) => void;
  busy: boolean;
}

const FILTERS: { value: RecommendedFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'remote', label: 'Remote' },
  { value: 'hybrid', label: 'Hybrid' },
  { value: 'onsite', label: 'Onsite' },
  { value: 'visa', label: 'Visa Sponsorship' },
  { value: 'relocation', label: 'Relocation Support' },
  { value: 'high', label: 'High Match (90%+)' },
  { value: 'new', label: 'New (24h)' },
];

function matchTone(score: number): 'success' | 'primary' | 'warning' {
  if (score >= 70) return 'success';
  if (score >= 50) return 'primary';
  return 'warning';
}

function confidenceTone(c?: string | null): 'success' | 'primary' | 'warning' {
  if (c === 'HIGH') return 'success';
  if (c === 'MEDIUM') return 'primary';
  return 'warning';
}

export function RecommendedJobs({ onApply, onSave, busy }: RecommendedJobsProps) {
  const [filter, setFilter] = useState<RecommendedFilter>('all');
  const [explainJob, setExplainJob] = useState<{ id: string; title: string } | null>(null);

  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useInfiniteQuery<RecommendedJobsResponse>({
      queryKey: ['jobs', 'recommended', filter],
      initialPageParam: 0,
      queryFn: async ({ pageParam }) =>
        (await api.get('/api/jobs/recommended', { params: { filter, page: pageParam, size: PAGE_SIZE } })).data,
      getNextPageParam: (lastPage) => (lastPage.hasMore ? (lastPage.page ?? 0) + 1 : undefined),
    });

  const selectFilter = (f: RecommendedFilter) => {
    setFilter(f);
    trackJobEvent('filter', { filter: f });
  };
  const handleApply = (jobId: string) => {
    trackJobEvent('apply', { jobId });
    onApply(jobId);
  };
  const handleSave = (jobId: string) => {
    trackJobEvent('save', { jobId });
    onSave(jobId);
  };
  const openExplain = (id: string, title: string) => {
    trackJobEvent('why_match', { jobId: id });
    setExplainJob({ id, title });
  };

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-24 rounded-xl" />
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-40 rounded-xl" />
        ))}
      </div>
    );
  }

  const firstPage = data?.pages[0];
  if (!firstPage?.profile) {
    return (
      <EmptyState
        icon={Sparkles}
        title="Run the AI workflow to unlock recommendations"
        description="Recommended Jobs are personalized once the AI workflow has analyzed a resume. Start a run from the Workflow page to build your candidate profile."
        action={
          <Link to="/workflow">
            <Button>
              <Sparkles className="h-4 w-4" /> Go to Workflow
            </Button>
          </Link>
        }
      />
    );
  }

  const profile = firstPage.profile;
  const jobs = data!.pages.flatMap((p) => p.jobs);
  const total = firstPage.total ?? jobs.length;

  return (
    <div className="space-y-6">
      <Card className="p-5">
        <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground">
          <Briefcase className="h-4 w-4" /> Candidate profile
        </h2>
        <div className="grid gap-4 sm:grid-cols-4">
          <div>
            <p className="text-xs text-muted-foreground">Experience</p>
            <p className="text-base font-semibold text-foreground">
              {profile.yearsExperience != null ? `${profile.yearsExperience} Years` : '—'}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Top skills</p>
            <p className="text-sm font-medium text-foreground">
              {profile.topSkills.length ? profile.topSkills.slice(0, 4).join(' • ') : '—'}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Preferred roles</p>
            <p className="text-sm font-medium text-foreground">
              {profile.preferredRoles.length ? profile.preferredRoles.join(' • ') : '—'}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Resume score</p>
            <p className="text-base font-semibold text-foreground">
              {profile.resumeScore != null ? `${profile.resumeScore}/100` : '—'}
            </p>
          </div>
        </div>
      </Card>

      <div className="flex flex-wrap items-center gap-2">
        {FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => selectFilter(f.value)}
            className={cn(
              'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
              filter === f.value
                ? 'border-primary bg-primary/10 text-primary'
                : 'border-border text-muted-foreground hover:bg-muted',
            )}
          >
            {f.label}
          </button>
        ))}
        {jobs.length > 0 && (
          <span className="ml-auto text-xs text-muted-foreground">
            {jobs.length} of {total} shown
          </span>
        )}
      </div>

      {jobs.length === 0 ? (
        <EmptyState
          icon={Building2}
          title={filter === 'all' ? 'No high-confidence matches yet' : 'No matches for this filter'}
          description={
            filter === 'all'
              ? 'Recommended shows roles scoring 70%+ against your profile with at least one matching role and three matching skills. Set your preferences or run a discovery pass to surface more — lower-scoring roles appear under Browse.'
              : 'Try a different filter, or check the Browse tab for more roles.'
          }
        />
      ) : (
        <div className="space-y-4">
          {jobs.map((rec, i) => (
            <RecommendedJobCard
              key={rec.job.id}
              rec={rec}
              index={i}
              onApply={handleApply}
              onSave={handleSave}
              onExplain={() => openExplain(rec.job.id, rec.job.title)}
              busy={busy}
            />
          ))}
          {hasNextPage && (
            <div className="flex justify-center pt-2">
              <Button variant="outline" onClick={() => fetchNextPage()} loading={isFetchingNextPage}>
                Load more
              </Button>
            </div>
          )}
        </div>
      )}

      <ExplainDialog
        jobId={explainJob?.id ?? null}
        jobTitle={explainJob?.title}
        onClose={() => setExplainJob(null)}
      />
    </div>
  );
}

function RecommendedJobCard({
  rec,
  index,
  onApply,
  onSave,
  onExplain,
  busy,
}: {
  rec: RecommendedJob;
  index: number;
  onApply: (jobId: string) => void;
  onSave: (jobId: string) => void;
  onExplain: () => void;
  busy: boolean;
}) {
  const { job, matchScore, matchedSkills, missingSkills, confidenceLevel } = rec;
  const isHighMatch = matchScore >= 90;
  const meta: string[] = [];
  if (job.country) meta.push(job.country);
  if (job.requiredExperience != null) meta.push(`${job.requiredExperience}+ yrs exp`);
  if (job.salaryRange) meta.push(job.salaryRange);
  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: index * 0.04 }}>
      <Card className="p-5">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <h3 className="text-base font-semibold text-foreground">{job.title}</h3>
            <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
              <Building2 className="h-3.5 w-3.5" /> {job.company}
              {job.location && <span>• {job.location}</span>}
            </p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-1.5">
            <Badge tone={matchTone(matchScore)}>Match Score: {matchScore}%</Badge>
            {isHighMatch && <Badge tone="success" className="text-[10px]">High Match</Badge>}
            {confidenceLevel && (
              <Badge tone={confidenceTone(confidenceLevel)} className="text-[10px]">
                {confidenceLevel} confidence
              </Badge>
            )}
          </div>
        </div>

        <JobBadges job={job} className="mt-3" />

        {meta.length > 0 && (
          <p className="mt-2 text-xs text-muted-foreground">{meta.join('  •  ')}</p>
        )}

        {(matchedSkills.length > 0 || missingSkills.length > 0) && (
          <div className="mt-3 space-y-1.5">
            {matchedSkills.length > 0 && (
              <p className="flex flex-wrap items-center gap-1.5 text-xs text-success">
                {matchedSkills.map((s) => (
                  <span key={s} className="flex items-center gap-1">
                    <CheckCircle2 className="h-3.5 w-3.5" /> {s}
                  </span>
                ))}
              </p>
            )}
            {missingSkills.length > 0 && (
              <p className="flex flex-wrap items-center gap-1.5 text-xs text-warning">
                {missingSkills.map((s) => (
                  <span key={s} className="flex items-center gap-1">
                    <XCircle className="h-3.5 w-3.5" /> {s}
                  </span>
                ))}
              </p>
            )}
          </div>
        )}

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={() => onApply(job.id)} disabled={busy}>
            <Send className="h-3.5 w-3.5" /> Apply
          </Button>
          <Button size="sm" variant="outline" onClick={() => onSave(job.id)} disabled={busy}>
            <Bookmark className="h-3.5 w-3.5" /> Save
          </Button>
          <Button size="sm" variant="ghost" onClick={onExplain}>
            <HelpCircle className="h-3.5 w-3.5" /> Why am I a match?
          </Button>
          {missingSkills.length > 0 && (
            <Button size="sm" variant="ghost" onClick={onExplain}>
              <XCircle className="h-3.5 w-3.5" /> Skill gap ({missingSkills.length})
            </Button>
          )}
        </div>
      </Card>
    </motion.div>
  );
}
