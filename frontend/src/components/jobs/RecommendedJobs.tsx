import { useQuery } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import { Link } from 'react-router-dom';
import {
  Bookmark,
  Briefcase,
  Building2,
  CheckCircle2,
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
import type { RecommendedJob, RecommendedJobsResponse } from '@/types/workflow';

interface RecommendedJobsProps {
  onApply: (jobId: string) => void;
  onSave: (jobId: string) => void;
  busy: boolean;
}

function matchTone(score: number): 'success' | 'primary' | 'warning' {
  if (score >= 75) return 'success';
  if (score >= 50) return 'primary';
  return 'warning';
}

export function RecommendedJobs({ onApply, onSave, busy }: RecommendedJobsProps) {
  const { data, isLoading } = useQuery<RecommendedJobsResponse>({
    queryKey: ['jobs', 'recommended'],
    queryFn: async () => (await api.get('/api/jobs/recommended')).data,
  });

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

  if (!data?.profile) {
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

  const { profile, jobs } = data;

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

      {jobs.length === 0 ? (
        <EmptyState
          icon={Building2}
          title="No jobs to recommend yet"
          description="Add some job postings on the Browse tab so we can rank them against your profile."
        />
      ) : (
        <div className="space-y-4">
          {jobs.map((rec, i) => (
            <RecommendedJobCard key={rec.job.id} rec={rec} index={i} onApply={onApply} onSave={onSave} busy={busy} />
          ))}
        </div>
      )}
    </div>
  );
}

function RecommendedJobCard({
  rec,
  index,
  onApply,
  onSave,
  busy,
}: {
  rec: RecommendedJob;
  index: number;
  onApply: (jobId: string) => void;
  onSave: (jobId: string) => void;
  busy: boolean;
}) {
  const { job, matchScore, matchedSkills, missingSkills } = rec;
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
          <Badge tone={matchTone(matchScore)} className={cn('shrink-0')}>
            Match Score: {matchScore}%
          </Badge>
        </div>

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

        <div className="mt-4 flex items-center gap-2">
          <Button size="sm" onClick={() => onApply(job.id)} disabled={busy}>
            <Send className="h-3.5 w-3.5" /> Apply
          </Button>
          <Button size="sm" variant="outline" onClick={() => onSave(job.id)} disabled={busy}>
            <Bookmark className="h-3.5 w-3.5" /> Save
          </Button>
        </div>
      </Card>
    </motion.div>
  );
}
