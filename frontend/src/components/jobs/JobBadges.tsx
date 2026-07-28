import { Flame, Globe, Plane, Stamp, Star, MapPin, TrendingUp } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/cn';
import type { Job } from '@/types/workflow';

const PRIORITY_TONE: Record<string, 'danger' | 'warning' | 'primary' | 'neutral'> = {
  CRITICAL: 'danger',
  HIGH: 'warning',
  MEDIUM: 'primary',
  LOW: 'neutral',
};

/** Phase 2C priority chip — job discovery / recommendation priority engine (nullable, dark by default). */
export function PriorityBadge({ priority }: { priority?: string | null }) {
  if (!priority) return null;
  return (
    <Badge tone={PRIORITY_TONE[priority] ?? 'neutral'}>
      <Flame className="h-3 w-3" /> {priority.charAt(0) + priority.slice(1).toLowerCase()} priority
    </Badge>
  );
}

/** Phase 2C must-apply flag — surfaced only when true. */
export function MustApplyBadge({ mustApply }: { mustApply?: boolean | null }) {
  if (!mustApply) return null;
  return (
    <Badge tone="danger">
      <Star className="h-3 w-3" /> Must apply
    </Badge>
  );
}

/** Phase 3B.1/3B.1.1 career-relevance match-strength chip, e.g. "Strong Match". */
export function MatchStrengthBadge({ matchStrength }: { matchStrength?: string | null }) {
  if (!matchStrength) return null;
  const s = matchStrength.toLowerCase();
  const tone = s.includes('excellent') || s.includes('strong')
    ? 'success'
    : s.includes('good')
      ? 'primary'
      : s.includes('weak')
        ? 'warning'
        : 'danger';
  return <Badge tone={tone}>{matchStrength}</Badge>;
}

const TIER_TONE: Record<string, 'success' | 'primary' | 'neutral'> = {
  TIER_1: 'success',
  TIER_2: 'primary',
  TIER_3: 'neutral',
};

/** International Job Discovery Engine, Phase 1 — country-tier chip (TIER_1/2/3). */
export function CountryTierBadge({ tier }: { tier?: string | null }) {
  if (!tier) return null;
  return (
    <Badge tone={TIER_TONE[tier] ?? 'neutral'}>
      <MapPin className="h-3 w-3" /> {tier.replace('_', ' ')}
    </Badge>
  );
}

/** International Job Discovery Engine, Phase 1 — seniority level chip (Senior/Staff/Principal/etc.). */
export function SeniorityBadge({ seniority }: { seniority?: string | null }) {
  if (!seniority) return null;
  return (
    <Badge tone="primary">
      <TrendingUp className="h-3 w-3" /> {seniority}
    </Badge>
  );
}

/**
 * Enrichment badges shared by the Recommended and discovered job cards. Every badge is
 * conditional on a nullable field, so manual/legacy jobs (which have none) render nothing.
 * `priority`/`mustApply` are optional Phase 2C fields carried on `RecommendedJob`, not `Job`
 * itself — passed in by callers that have that context (Recommended tab).
 */
export function JobBadges({
  job,
  className,
  priority,
  mustApply,
}: {
  job: Job;
  className?: string;
  priority?: string | null;
  mustApply?: boolean | null;
}) {
  const remoteType = job.remoteType ?? (job.remote ? 'REMOTE' : null);
  const hasAny = remoteType || job.sponsorshipAvailable || job.relocationSupport || priority || mustApply;
  if (!hasAny) return null;

  return (
    <div className={cn('flex flex-wrap items-center gap-1.5', className)}>
      {remoteType && (
        <Badge tone={remoteType === 'REMOTE' ? 'success' : remoteType === 'HYBRID' ? 'primary' : 'info'}>
          <Globe className="h-3 w-3" /> {remoteType.charAt(0) + remoteType.slice(1).toLowerCase()}
        </Badge>
      )}
      {job.sponsorshipAvailable && (
        <Badge tone="primary">
          <Stamp className="h-3 w-3" /> Visa Sponsorship
        </Badge>
      )}
      {job.relocationSupport && (
        <Badge tone="info">
          <Plane className="h-3 w-3" /> Relocation
        </Badge>
      )}
      <MustApplyBadge mustApply={mustApply} />
      <PriorityBadge priority={priority} />
    </div>
  );
}
