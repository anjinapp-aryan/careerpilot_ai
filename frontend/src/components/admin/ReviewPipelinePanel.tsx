import { useQuery } from '@tanstack/react-query';
import { ClipboardCheck } from 'lucide-react';
import { api } from '@/lib/api';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';

const HEALTH_TONE: Record<string, BadgeTone> = {
  UP: 'success',
  DEGRADED: 'warning',
  DOWN: 'danger',
  NOT_CONFIGURED: 'neutral',
};

/** Phase 7.12 diagnostics — GET /api/diagnostics/application-review. */
interface ReviewDiagnostics {
  enabled: boolean;
  health: string;
  triggerEnabled: boolean;
  resumeReviewerEnabled: boolean;
  atsReviewerEnabled: boolean;
  companyReviewerEnabled: boolean;
  learningReviewerEnabled: boolean;
  consistencyReviewerEnabled: boolean;
  qualityReviewerEnabled: boolean;
  reviewsCompleted: number;
  averageReviewMs: number;
  averageQuality: number;
  executorQueueSize: number;
  executorQueueCapacity: number;
  failureCount: number;
  [k: string]: unknown;
}

function tone(v?: string | null): BadgeTone {
  return HEALTH_TONE[(v ?? '').toUpperCase()] ?? 'neutral';
}

const REVIEWERS: { key: keyof ReviewDiagnostics; label: string }[] = [
  { key: 'resumeReviewerEnabled', label: 'Resume' },
  { key: 'atsReviewerEnabled', label: 'ATS' },
  { key: 'companyReviewerEnabled', label: 'Company' },
  { key: 'learningReviewerEnabled', label: 'Learning' },
  { key: 'consistencyReviewerEnabled', label: 'Consistency' },
  { key: 'qualityReviewerEnabled', label: 'Quality' },
];

const VERDICTS: { key: string; label: string; tone: BadgeTone }[] = [
  { key: 'verdict.READY', label: 'Ready', tone: 'success' },
  { key: 'verdict.HUMAN_REVIEW', label: 'Human review', tone: 'warning' },
  { key: 'verdict.BLOCKED', label: 'Blocked', tone: 'danger' },
];

/**
 * Phase 7.12 — AI Review Pipeline health. Reads the no-auth review diagnostics endpoint (same
 * convention as every other admin panel). Ships dark: with stock flags it reads NOT_CONFIGURED and all
 * counts are zero. No fabricated data — counts come straight from the backend.
 */
export function ReviewPipelinePanel() {
  const { data: d, isLoading } = useQuery<ReviewDiagnostics>({
    queryKey: ['diagnostics', 'application-review'],
    queryFn: async () => (await api.get('/api/diagnostics/application-review')).data,
    retry: false,
  });

  const num = (k: string) => (typeof d?.[k] === 'number' ? (d[k] as number) : 0);

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle className="flex items-center gap-2 text-base">
          <ClipboardCheck className="h-4 w-4 text-muted-foreground" /> AI Review Pipeline (Phase 7.12)
        </CardTitle>
        {d && <Badge tone={tone(d.health)}>{d.health}</Badge>}
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-28 w-full" />
        ) : !d ? (
          <p className="text-sm text-muted-foreground">Review diagnostics unavailable.</p>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap gap-1.5">
              <Badge tone={d.enabled ? 'success' : 'neutral'} className="text-[10px]">
                Pipeline: {d.enabled ? 'on' : 'off'}
              </Badge>
              <Badge tone={d.triggerEnabled ? 'success' : 'neutral'} className="text-[10px]">
                Auto trigger: {d.triggerEnabled ? 'on' : 'off'}
              </Badge>
              {REVIEWERS.map((r) => (
                <Badge key={r.label} tone={d[r.key] ? 'success' : 'neutral'} className="text-[10px]">
                  {r.label}: {d[r.key] ? 'on' : 'off'}
                </Badge>
              ))}
            </div>

            <div className="grid gap-3 sm:grid-cols-4">
              <Stat label="Reviews" value={d.reviewsCompleted} />
              <Stat label="Avg quality" value={d.averageQuality} />
              <Stat label="Avg review (ms)" value={d.averageReviewMs} />
              <Stat label="Queue" value={`${d.executorQueueSize}/${d.executorQueueCapacity}`} />
            </div>

            <div>
              <p className="mb-1.5 text-xs font-medium text-muted-foreground">Final verdicts</p>
              <div className="flex flex-wrap gap-1.5">
                {VERDICTS.map((v) => (
                  <Badge key={v.key} tone={v.tone} className="text-[10px]">
                    {v.label}: {num(v.key)}
                  </Badge>
                ))}
                <Badge tone={num('failureCount') > 0 ? 'danger' : 'neutral'} className="text-[10px]">
                  Failures: {num('failureCount')}
                </Badge>
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                The review pipeline reviews the assembled package — it never edits the resume, ATS, or recommendation.
              </p>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded-lg border border-border bg-muted/30 px-3 py-2.5">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-lg font-semibold tabular-nums text-foreground">{value}</p>
    </div>
  );
}
