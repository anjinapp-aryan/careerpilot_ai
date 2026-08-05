import { useQuery } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router-dom';
import { Building2, Clock, ExternalLink, Target } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { companyIntel, type CandidateFit } from '@/lib/companyIntel';

/**
 * Phase 7.13 — reusable Company Intelligence panel for a company name (used in the Jobs flow and
 * Applications page). Dark-safe: while company.knowledge.enabled is off every call 404s → resolves
 * null → the panel renders nothing at all.
 */

function scoreTone(v?: number | null): BadgeTone {
  if (v == null) return 'neutral';
  if (v >= 70) return 'success';
  if (v >= 45) return 'warning';
  return 'danger';
}

const SCORE_LABELS: { key: string; label: string }[] = [
  { key: 'qualityScore', label: 'Quality' },
  { key: 'hiringProbability', label: 'Hiring odds' },
  { key: 'technologyMatch', label: 'Tech match' },
  { key: 'resumeCompatibility', label: 'Resume fit' },
  { key: 'interviewDifficulty', label: 'Interview difficulty' },
  { key: 'careerGrowth', label: 'Career growth' },
  { key: 'salaryPotential', label: 'Salary potential' },
  { key: 'cultureMatch', label: 'Culture match' },
  { key: 'learningValue', label: 'Learning value' },
  { key: 'longTermOpportunity', label: 'Long-term' },
];

const SECTION_LABELS: Record<string, string> = {
  profile: 'Profile',
  technologyStack: 'Technology stack',
  skillDemand: 'Skill demand',
  hiringPattern: 'Hiring pattern',
  interviewProcess: 'Interview process',
  culture: 'Culture',
  reputation: 'Reputation',
  salaryInsights: 'Salary insights',
  growthSignals: 'Growth signals',
  businessDomain: 'Business domain',
  learningHistory: 'Learning history',
};

const FIT_LABELS: { key: keyof CandidateFit; label: string }[] = [
  { key: 'overallFit', label: 'Overall fit' },
  { key: 'skillFit', label: 'Skill fit' },
  { key: 'experienceFit', label: 'Experience fit' },
  { key: 'technologyFit', label: 'Technology fit' },
  { key: 'locationFit', label: 'Location fit' },
  { key: 'salaryFit', label: 'Salary fit' },
  { key: 'visaFit', label: 'Visa fit' },
  { key: 'careerGoalFit', label: 'Career growth fit' },
  { key: 'leadershipFit', label: 'Leadership fit' },
  { key: 'architectureFit', label: 'Architecture fit' },
  { key: 'domainFit', label: 'Domain fit' },
  { key: 'cloudFit', label: 'Cloud fit' },
];

export function CompanyIntelPanel({
  companyName,
  jobId,
  showTimeline = false,
}: {
  companyName?: string | null;
  /** Phase 7.17 — when provided, also fetches and renders the unified Candidate Fit breakdown. */
  jobId?: string | null;
  showTimeline?: boolean;
}) {
  const summary = useQuery({
    queryKey: ['company-intel', 'by-name', companyName],
    queryFn: () => companyIntel.findByName(companyName),
    enabled: !!companyName?.trim(),
    staleTime: 5 * 60_000,
    retry: false,
  });

  const detail = useQuery({
    queryKey: ['company-intel', 'detail', summary.data?.id],
    queryFn: () => companyIntel.get(summary.data!.id),
    enabled: !!summary.data?.id,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const timeline = useQuery({
    queryKey: ['company-intel', 'timeline', summary.data?.id],
    queryFn: () => companyIntel.timeline(summary.data!.id),
    enabled: showTimeline && !!summary.data?.id,
    staleTime: 60_000,
    retry: false,
  });

  const fit = useQuery({
    queryKey: ['company-intel', 'fit', jobId],
    queryFn: () => companyIntel.fit(jobId!),
    enabled: !!jobId,
    staleTime: 60_000,
    retry: false,
  });

  const company = detail.data;
  const location = useLocation();
  const onDedicatedPage = location.pathname === '/companies';
  if (!company) return null; // dark flag, unknown company, or still loading — stay invisible

  const scores = company.scores as unknown as Record<string, number | null | undefined>;
  const sections = Object.entries(company.knowledge ?? {}).filter(([k]) => SECTION_LABELS[k]);
  const fullPageHref = `/companies?company=${encodeURIComponent(company.companyName)}${jobId ? `&jobId=${encodeURIComponent(jobId)}` : ''}`;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <Building2 className="h-4 w-4 text-primary" />
          Company intelligence — {company.companyName}
          <Badge tone="neutral">v{company.knowledgeVersion}</Badge>
        </CardTitle>
        {!onDedicatedPage && (
          <Link to={fullPageHref} className="flex items-center gap-1 text-sm font-medium text-primary hover:underline">
            Full profile <ExternalLink className="h-3.5 w-3.5" />
          </Link>
        )}
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2">
          {SCORE_LABELS.map(({ key, label }) =>
            scores[key] == null ? null : (
              <Badge key={key} tone={scoreTone(scores[key])} title={company.scoreExplanations?.[key]}>
                {label}: {scores[key]}
              </Badge>
            ),
          )}
        </div>

        {fit.data && (
          <div>
            <p className="mb-1.5 flex items-center gap-1 text-xs font-semibold text-foreground">
              <Target className="h-3 w-3" /> Candidate fit for this role
            </p>
            <div className="flex flex-wrap gap-2">
              {FIT_LABELS.map(({ key, label }) => {
                const value = fit.data![key] as number | null | undefined;
                const explanation = fit.data![`${key}Explanation` as keyof CandidateFit] as string | undefined;
                return value == null ? null : (
                  <Badge key={key} tone={scoreTone(value)} title={explanation}>
                    {label}: {value}
                  </Badge>
                );
              })}
              {fit.data.memoryInfluence != null && fit.data.memoryInfluence !== 0 && (
                <Badge tone={fit.data.memoryInfluence > 0 ? 'success' : 'danger'} title={fit.data.memoryInfluenceExplanation}>
                  Memory influence: {fit.data.memoryInfluence > 0 ? '+' : ''}{fit.data.memoryInfluence}
                </Badge>
              )}
            </div>
          </div>
        )}

        {company.insights.length > 0 && (
          <ul className="space-y-1">
            {company.insights.map((insight) => (
              <li key={insight.kind} className="text-xs text-muted-foreground">
                <span className="font-medium text-foreground">{insight.headline}</span> — {insight.detail}
              </li>
            ))}
          </ul>
        )}

        {sections.length > 0 && (
          <div className="space-y-2">
            {sections.slice(0, 6).map(([key, value]) => (
              <div key={key}>
                <p className="text-xs font-semibold text-foreground">{SECTION_LABELS[key]}</p>
                <p className="whitespace-pre-wrap text-xs text-muted-foreground">
                  {value.length > 500 ? `${value.slice(0, 500)}…` : value}
                </p>
              </div>
            ))}
          </div>
        )}

        {showTimeline && (timeline.data?.length ?? 0) > 0 && (
          <div>
            <p className="mb-1 flex items-center gap-1 text-xs font-semibold text-foreground">
              <Clock className="h-3 w-3" /> Company timeline
            </p>
            <ul className="space-y-1">
              {timeline.data!.slice(0, 10).map((event) => (
                <li key={event.id} className="text-xs text-muted-foreground">
                  {new Date(event.occurredAt).toLocaleDateString()} — {event.eventType.replaceAll('_', ' ').toLowerCase()}
                  {event.detail ? ` (${event.detail.slice(0, 80)})` : ''}
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
