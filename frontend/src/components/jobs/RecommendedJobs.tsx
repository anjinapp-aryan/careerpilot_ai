import { useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import { Link } from 'react-router-dom';
import {
  Bookmark,
  Briefcase,
  Building2,
  CheckCircle2,
  ExternalLink,
  FileCheck,
  HelpCircle,
  Send,
  Sparkles,
  TrendingUp,
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
import { RelevanceDrawer } from '@/components/jobs/RelevanceDrawer';
import { PrepareApplicationDrawer } from '@/components/jobs/PrepareApplicationDrawer';
import { JobBadges, SponsorshipBadge, FreshnessBadge } from '@/components/jobs/JobBadges';
import { trackJobEvent } from '@/lib/jobTelemetry';
import type { RecommendedFilter, RecommendedJob, RecommendedJobsResponse, ScoreBreakdown } from '@/types/workflow';

const PAGE_SIZE = 10;

interface RecommendedJobsProps {
  onApply: (jobId: string) => void;
  onSave: (jobId: string) => void;
  busy: boolean;
}

const FILTERS: { value: RecommendedFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'new', label: "Today's Jobs" },
  { value: 'must-apply', label: 'Must Apply' },
  { value: 'high-priority', label: 'High Priority' },
  { value: 'human-review', label: 'Human Review' },
  { value: 'remote', label: 'Remote' },
  { value: 'hybrid', label: 'Hybrid' },
  { value: 'onsite', label: 'Onsite' },
  { value: 'visa', label: 'Visa Sponsorship' },
  { value: 'relocation', label: 'Relocation Support' },
  { value: 'high', label: 'High Match (90%+)' },
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

/** Global Job Discovery Expansion — client-side country quick-filter, applied over jobs already
 *  returned by the existing /api/jobs/recommended call. No extra API call: the country list and
 *  the filtering both work off `job.country`, already present on every RecommendedJob. */
const COUNTRY_FLAGS: Record<string, string> = {
  Germany: '🇩🇪',
  Netherlands: '🇳🇱',
  'United Kingdom': '🇬🇧',
  Ireland: '🇮🇪',
  Canada: '🇨🇦',
  Australia: '🇦🇺',
  'United States': '🇺🇸',
  Singapore: '🇸🇬',
  'United Arab Emirates': '🇦🇪',
};

export function RecommendedJobs({ onApply, onSave, busy }: RecommendedJobsProps) {
  const [filter, setFilter] = useState<RecommendedFilter>('all');
  const [countryFilter, setCountryFilter] = useState<string | null>(null);
  const [explainJob, setExplainJob] = useState<{
    id: string;
    title: string;
    breakdown?: ScoreBreakdown | null;
    matchScore?: number | null;
    country?: string | null;
  } | null>(null);
  const [relevanceJob, setRelevanceJob] = useState<RecommendedJob | null>(null);
  const [prepareJob, setPrepareJob] = useState<RecommendedJob | null>(null);

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
  const openExplain = (rec: RecommendedJob) => {
    trackJobEvent('why_match', { jobId: rec.job.id });
    setExplainJob({
      id: rec.job.id,
      title: rec.job.title,
      breakdown: rec.scoreBreakdown ?? null,
      matchScore: rec.matchScore,
      country: rec.job.country ?? null,
    });
  };
  const openRelevance = (rec: RecommendedJob) => {
    trackJobEvent('why_seeing', { jobId: rec.job.id });
    setRelevanceJob(rec);
  };
  // Prepare Application is a read-only preview — it never calls onApply itself. It exists
  // alongside Apply, not upstream of it: opening the drawer never starts the existing
  // application-execution pipeline, and closing it never triggers anything either.
  const openPrepare = (rec: RecommendedJob) => {
    trackJobEvent('prepare_application_started', { jobId: rec.job.id });
    setPrepareJob(rec);
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
  const allJobs = data!.pages.flatMap((p) => p.jobs);
  const total = firstPage.total ?? allJobs.length;

  // Country chips only ever list countries actually present in the already-loaded jobs — never a
  // hardcoded 9-country list that could show a country with zero results.
  const countriesPresent = Array.from(
    new Set(allJobs.map((r) => r.job.country).filter((c): c is string => !!c)),
  ).sort();
  const jobs = countryFilter ? allJobs.filter((r) => r.job.country === countryFilter) : allJobs;

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

      {countriesPresent.length > 1 && (
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => setCountryFilter(null)}
            className={cn(
              'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
              countryFilter === null
                ? 'border-primary bg-primary/10 text-primary'
                : 'border-border text-muted-foreground hover:bg-muted',
            )}
          >
            All countries
          </button>
          {countriesPresent.map((country) => (
            <button
              key={country}
              onClick={() => setCountryFilter(country === countryFilter ? null : country)}
              className={cn(
                'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
                countryFilter === country
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border text-muted-foreground hover:bg-muted',
              )}
            >
              {COUNTRY_FLAGS[country] ? `${COUNTRY_FLAGS[country]} ` : ''}{country}
            </button>
          ))}
        </div>
      )}

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
              onExplain={() => openExplain(rec)}
              onRelevance={() => openRelevance(rec)}
              onPrepare={() => openPrepare(rec)}
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
        breakdown={explainJob?.breakdown}
        matchScore={explainJob?.matchScore}
        country={explainJob?.country}
        onClose={() => setExplainJob(null)}
      />
      <RelevanceDrawer
        jobId={relevanceJob?.job.id ?? null}
        jobTitle={relevanceJob?.job.title}
        rec={relevanceJob}
        onClose={() => setRelevanceJob(null)}
      />
      <PrepareApplicationDrawer
        rec={prepareJob}
        onClose={() => setPrepareJob(null)}
        onApply={(jobId) => {
          setPrepareJob(null);
          handleApply(jobId);
        }}
      />
    </div>
  );
}

export function RecommendedJobCard({
  rec,
  index,
  onApply,
  onSave,
  onExplain,
  onRelevance,
  onPrepare,
  busy,
}: {
  rec: RecommendedJob;
  index: number;
  onApply: (jobId: string) => void;
  onSave: (jobId: string) => void;
  onExplain: () => void;
  onRelevance: () => void;
  /** Opens the read-only Prepare Application preview. Optional so existing callers/tests that
   *  don't need it keep compiling unchanged. */
  onPrepare?: () => void;
  busy: boolean;
}) {
  const { job, matchScore, matchedSkills, missingSkills, confidenceLevel } = rec;
  const isHighMatch = matchScore >= 90;
  const learningBoost = rec.scoreBreakdown?.learningBoost ?? 0;
  const meta: string[] = [];
  if (job.country) meta.push(job.country);
  if (job.requiredExperience != null) meta.push(`${job.requiredExperience}+ yrs exp`);
  if (job.salaryRange) meta.push(job.salaryRange);
  const discoveryDate = job.postedDate ?? job.createdAt;
  if (discoveryDate) meta.push(`Discovered ${new Date(discoveryDate).toLocaleDateString()}`);

  // The real employer/source URL, exactly as captured during discovery — never constructed or
  // guessed from the company name. sourceUrl takes precedence over externalUrl, the same
  // precedence GuestApplyAutomationService#applyUrl uses, so this is provably the same URL any
  // later Guided Apply run would actually navigate to.
  const employerUrl = job.sourceUrl || job.externalUrl || null;
  const atsLabel = rec.atsPlatform
    ? rec.atsPlatform.charAt(0) + rec.atsPlatform.slice(1).toLowerCase()
    : null;

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
            {rec.category && (
              <Badge tone="neutral" className="text-[10px]">{rec.category.replaceAll('_', ' ')}</Badge>
            )}
            {learningBoost !== 0 && (
              <Badge
                tone={learningBoost > 0 ? 'success' : 'warning'}
                className="flex items-center gap-1 text-[10px]"
                title="Improved using historical learning."
              >
                <TrendingUp className="h-3 w-3" /> Learning {learningBoost > 0 ? '+' : ''}{learningBoost}
              </Badge>
            )}
          </div>
        </div>

        <JobBadges job={job} className="mt-3" priority={rec.priority} mustApply={rec.mustApply} />
        {(job.sponsorshipStatus || rec.freshness) && (
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <SponsorshipBadge status={job.sponsorshipStatus} />
            <FreshnessBadge freshness={rec.freshness} />
          </div>
        )}

        {/* Employer identity — the trust anchor for this card. Company name is always real (it's
            the same field shown in the header); "Careers" is a display label, not a claim about a
            distinct source system. ATS is shown only when genuinely detected from the real URL —
            never guessed — so a company with an unrecognised careers portal simply omits it. */}
        <p className="mt-2 text-xs text-muted-foreground">
          Source: {job.company} Careers{atsLabel && <> · ATS: {atsLabel}</>}
        </p>

        {meta.length > 0 && (
          <p className="mt-1 text-xs text-muted-foreground">{meta.join('  •  ')}</p>
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

        {/* Action hierarchy (section 3): Apply is primary — it is the existing, unmodified
            application-execution entry point and must look like the main action. Prepare
            Application is secondary (outline) — it only opens a read-only preview, never
            executes anything. Save is a lighter supporting action. View Employer Job is kept
            visually distinct on its own line, as external verification rather than a competing
            in-app action. */}
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={() => onApply(job.id)} disabled={busy} title="Starts the existing application process">
            <Send className="h-3.5 w-3.5" /> Apply
          </Button>
          {onPrepare && (
            <Button
              size="sm"
              variant="outline"
              onClick={onPrepare}
              title="Preview match details, skill gaps and the employer posting — does not submit anything"
            >
              <FileCheck className="h-3.5 w-3.5" /> Prepare Application
            </Button>
          )}
          <Button size="sm" variant="outline" onClick={() => onSave(job.id)} disabled={busy}>
            <Bookmark className="h-3.5 w-3.5" /> Save
          </Button>
        </div>

        <div className="mt-2">
          {employerUrl ? (
            <a
              href={employerUrl}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={`View Employer Job — opens ${job.company}'s real posting in a new tab`}
              className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
            >
              View Employer Job <ExternalLink className="h-3 w-3" />
            </a>
          ) : (
            <span className="text-xs text-muted-foreground" title="This job has no captured employer URL">
              Employer posting unavailable
            </span>
          )}
        </div>

        <div className="mt-2 flex flex-wrap items-center gap-2">
          <Button size="sm" variant="ghost" onClick={onExplain}>
            <HelpCircle className="h-3.5 w-3.5" /> Why am I a match?
          </Button>
          <Button size="sm" variant="ghost" onClick={onRelevance}>
            <HelpCircle className="h-3.5 w-3.5" /> Why am I seeing this?
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
