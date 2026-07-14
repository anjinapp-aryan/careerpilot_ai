import { Badge, type BadgeTone } from '@/components/ui/badge';
import type { ApplicationHealthStatus } from '@/types/workflow';

const TONE: Record<ApplicationHealthStatus, BadgeTone> = {
  EXCELLENT: 'success',
  HEALTHY: 'success',
  NEEDS_ATTENTION: 'warning',
  RISK: 'danger',
  COLD: 'neutral',
  STALE: 'warning',
};

const LABEL: Record<ApplicationHealthStatus, string> = {
  EXCELLENT: 'Excellent',
  HEALTHY: 'Healthy',
  NEEDS_ATTENTION: 'Needs attention',
  RISK: 'At risk',
  COLD: 'Cold',
  STALE: 'Stale',
};

/** Small colored badge for an application's deterministic health bucket (ApplicationHealthService). */
export function ApplicationHealthBadge({ status, className }: { status: ApplicationHealthStatus; className?: string }) {
  return (
    <Badge tone={TONE[status]} className={className}>
      {LABEL[status]}
    </Badge>
  );
}
