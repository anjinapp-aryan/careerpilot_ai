import { Globe, Plane, Stamp } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/cn';
import type { Job } from '@/types/workflow';

/**
 * Enrichment badges shared by the Recommended and discovered job cards. Every badge is
 * conditional on a nullable field, so manual/legacy jobs (which have none) render nothing.
 */
export function JobBadges({ job, className }: { job: Job; className?: string }) {
  const remoteType = job.remoteType ?? (job.remote ? 'REMOTE' : null);
  const hasAny = remoteType || job.sponsorshipAvailable || job.relocationSupport;
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
    </div>
  );
}
