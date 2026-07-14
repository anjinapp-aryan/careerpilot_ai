import { useQuery } from '@tanstack/react-query';
import { Send } from 'lucide-react';
import { api } from '@/lib/api';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import type { ApplicationSubmissionDiagnostics } from '@/lib/applicationSubmission';

const HEALTH_TONE: Record<string, BadgeTone> = {
  UP: 'success',
  DEGRADED: 'warning',
  DOWN: 'danger',
  NOT_CONFIGURED: 'neutral',
};

const FLAGS: { key: keyof ApplicationSubmissionDiagnostics; label: string }[] = [
  { key: 'autoEnabled', label: 'Auto' },
  { key: 'manualEnabled', label: 'Manual' },
  { key: 'approvalEnabled', label: 'Approval gate' },
];

/**
 * Phase 7.16 — admin health panel for the Real Application Submission Pipeline (counts-only
 * diagnostics, same convention as {@code AutopilotHealthPanel}/{@code StoryIntelligencePanel}).
 * Success rate, queue depth, approval-queue size, provider distribution are all derived server-side
 * from existing execution/approval rows plus the new session table — no new metrics collection here.
 */
export function SubmissionPipelinePanel() {
  const { data, isLoading } = useQuery({
    queryKey: ['diagnostics', 'application-submission'],
    queryFn: async () =>
      (await api.get<ApplicationSubmissionDiagnostics>('/api/diagnostics/application-submission')).data,
    refetchInterval: 60_000,
    retry: false,
  });

  const successRate =
    data && data.sessionsTotal > 0 ? Math.round((data.sessionsCompleted / data.sessionsTotal) * 100) : null;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <Send className="h-4 w-4 text-primary" />
          Application submission pipeline
          {data && <Badge tone={HEALTH_TONE[data.health] ?? 'neutral'}>{data.health}</Badge>}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading || !data ? (
          <Skeleton className="h-20 w-full" />
        ) : (
          <>
            <div className="flex flex-wrap gap-1">
              <Badge tone={data.enabled ? 'success' : 'neutral'}>Engine {data.enabled ? 'on' : 'off'}</Badge>
              {FLAGS.map(({ key, label }) => (
                <Badge key={String(key)} tone={data[key] ? 'success' : 'neutral'}>
                  {label} {data[key] ? 'on' : 'off'}
                </Badge>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-2 text-xs text-muted-foreground sm:grid-cols-4">
              <div>Sessions: <span className="text-foreground">{data.sessionsTotal}</span></div>
              <div>Completed: <span className="text-foreground">{data.sessionsCompleted}</span></div>
              <div>Failed: <span className="text-foreground">{data.sessionsFailed}</span></div>
              <div>Success rate: <span className="text-foreground">{successRate === null ? 'n/a' : `${successRate}%`}</span></div>
              <div>Waiting approval: <span className="text-foreground">{data.sessionsWaitingApproval}</span></div>
              <div>Submitting: <span className="text-foreground">{data.sessionsSubmitting}</span></div>
              <div>
                Queue: <span className="text-foreground">{data.executorQueueSize}/{data.executorQueueCapacity}</span>
              </div>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
