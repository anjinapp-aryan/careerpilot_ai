import { Award, Briefcase, Globe2, Wrench } from 'lucide-react';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import type {
  OptimizationDimensionFinding,
  OptimizationEvidence,
  OptimizationResponse,
} from '@/types/workflow';

/**
 * "What CareerPilot Learned From Your Behavior" — the section the old page never had. Backed
 * entirely by `GET /api/intelligence/optimization` (Phase 13B/13C, already built, never wired to
 * any page before this): real per-country/company/skill success rates computed from this user's
 * own application/interview/offer history, each with an evidence citation. Confidence is a band
 * (INSUFFICIENT/LOW/MEDIUM/HIGH), never a fabricated percentage — see `Evidence.java`'s own
 * rationale: a 33% rate from 3 applications is not a fact worth acting on.
 */

const CONFIDENCE_TONE: Record<string, BadgeTone> = {
  HIGH: 'success',
  MEDIUM: 'primary',
  LOW: 'warning',
  INSUFFICIENT: 'neutral',
};

function EvidenceLine({ evidence }: { evidence: OptimizationEvidence }) {
  return (
    <p className="mt-1 text-[11px] text-muted-foreground" title={evidence.citation}>
      {evidence.citation}
    </p>
  );
}

function DimensionGroup({
  icon: Icon,
  title,
  findings,
}: {
  icon: typeof Globe2;
  title: string;
  findings: OptimizationDimensionFinding[];
}) {
  const actionable = findings.filter((f) => f.evidence.confidence !== 'INSUFFICIENT');
  if (actionable.length === 0) return null;

  return (
    <div>
      <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold text-foreground">
        <Icon className="h-3.5 w-3.5 text-primary" /> {title}
      </p>
      <div className="space-y-2">
        {actionable.slice(0, 5).map((f) => (
          <div key={f.key} className="rounded-lg border border-border p-2.5">
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-medium text-foreground">{f.key}</span>
              <div className="flex items-center gap-1.5">
                {f.successRate != null && (
                  <span className="text-xs font-semibold tabular-nums text-foreground">
                    {Math.round(f.successRate * 100)}% success
                  </span>
                )}
                <Badge tone={CONFIDENCE_TONE[f.evidence.confidence]} className="text-[10px]">
                  {f.evidence.confidence.toLowerCase()}
                </Badge>
              </div>
            </div>
            <EvidenceLine evidence={f.evidence} />
          </div>
        ))}
      </div>
    </div>
  );
}

export function LearnedPatterns({ data }: { data: OptimizationResponse | undefined }) {
  if (!data?.enabled) return null;
  const snapshot = data.snapshot;
  if (!snapshot) return null;

  const resumeEvidence = snapshot.resume?.evidence;
  const hasResume = snapshot.resume?.recommendedVersion && resumeEvidence?.confidence !== 'INSUFFICIENT';
  const hasCountries = snapshot.countries.some((f) => f.evidence.confidence !== 'INSUFFICIENT');
  const hasCompanies = snapshot.companies.some((f) => f.evidence.confidence !== 'INSUFFICIENT');
  const hasSkills = snapshot.skills.some((f) => f.evidence.confidence !== 'INSUFFICIENT');
  const nothingActionable = !hasResume && !hasCountries && !hasCompanies && !hasSkills;

  return (
    <Card>
      <CardHeader className="flex-row items-center gap-2">
        <Wrench className="h-4 w-4 text-primary" />
        <CardTitle className="text-base">What CareerPilot learned from your behavior</CardTitle>
      </CardHeader>
      <CardContent>
        {nothingActionable ? (
          <p className="text-sm text-muted-foreground">
            {data.message || 'Not enough application history yet to find a real pattern — this fills in as you apply, interview, and hear back.'}
          </p>
        ) : (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {hasResume && snapshot.resume && (
              <div>
                <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold text-foreground">
                  <Award className="h-3.5 w-3.5 text-primary" /> Best-performing resume
                </p>
                <div className="rounded-lg border border-border p-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-medium text-foreground">{snapshot.resume.recommendedVersion}</span>
                    {resumeEvidence && (
                      <Badge tone={CONFIDENCE_TONE[resumeEvidence.confidence]} className="text-[10px]">
                        {resumeEvidence.confidence.toLowerCase()}
                      </Badge>
                    )}
                  </div>
                  {snapshot.resume.interviewRate != null && (
                    <p className="mt-1 text-xs text-muted-foreground">
                      {Math.round(snapshot.resume.interviewRate * 100)}% interview rate across {snapshot.resume.versionsCompared} version(s) compared
                    </p>
                  )}
                  {resumeEvidence && <EvidenceLine evidence={resumeEvidence} />}
                </div>
              </div>
            )}
            <DimensionGroup icon={Globe2} title="Countries that work for you" findings={snapshot.countries} />
            <DimensionGroup icon={Briefcase} title="Companies you respond well to" findings={snapshot.companies} />
            <DimensionGroup icon={Wrench} title="Skills that get interviews" findings={snapshot.skills} />
          </div>
        )}
      </CardContent>
    </Card>
  );
}
