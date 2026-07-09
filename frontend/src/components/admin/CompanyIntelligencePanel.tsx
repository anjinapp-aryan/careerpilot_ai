import { useQuery } from '@tanstack/react-query';
import { Network } from 'lucide-react';
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

/** Phase 7.13 diagnostics — GET /api/diagnostics/company. */
interface CompanyDiagnostics {
  enabled: boolean;
  health: string;
  graphEnabled: boolean;
  similarityEnabled: boolean;
  timelineEnabled: boolean;
  copilotEnabled: boolean;
  resumeEnabled: boolean;
  learningEnabled: boolean;
  analyticsEnabled: boolean;
  recommendationEnabled: boolean;
  companiesKnown: number;
  knowledgeVersions: number;
  timelineEvents: number;
  graphEdges: number;
  executorQueueSize: number;
  executorQueueCapacity: number;
  [k: string]: unknown;
}

const FLAGS: { key: keyof CompanyDiagnostics; label: string }[] = [
  { key: 'graphEnabled', label: 'Graph' },
  { key: 'similarityEnabled', label: 'Similarity' },
  { key: 'timelineEnabled', label: 'Timeline' },
  { key: 'learningEnabled', label: 'Learning' },
  { key: 'recommendationEnabled', label: 'Recommendation' },
  { key: 'resumeEnabled', label: 'Resume' },
  { key: 'copilotEnabled', label: 'Copilot' },
  { key: 'analyticsEnabled', label: 'Analytics' },
];

/** Phase 7.13 — admin health panel for the Company Knowledge Graph (counts-only diagnostics). */
export function CompanyIntelligencePanel() {
  const { data, isLoading } = useQuery({
    queryKey: ['diagnostics', 'company'],
    queryFn: async () => (await api.get<CompanyDiagnostics>('/api/diagnostics/company')).data,
    refetchInterval: 60_000,
    retry: false,
  });

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <Network className="h-4 w-4 text-primary" />
          Company intelligence
          {data && <Badge tone={HEALTH_TONE[data.health] ?? 'neutral'}>{data.health}</Badge>}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading || !data ? (
          <Skeleton className="h-20 w-full" />
        ) : (
          <>
            <div className="flex flex-wrap gap-1">
              <Badge tone={data.enabled ? 'success' : 'neutral'}>Knowledge {data.enabled ? 'on' : 'off'}</Badge>
              {FLAGS.map(({ key, label }) => (
                <Badge key={String(key)} tone={data[key] ? 'success' : 'neutral'}>
                  {label} {data[key] ? 'on' : 'off'}
                </Badge>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-2 text-xs text-muted-foreground sm:grid-cols-4">
              <div>Companies: <span className="text-foreground">{fmt(data.companiesKnown)}</span></div>
              <div>Versions: <span className="text-foreground">{fmt(data.knowledgeVersions)}</span></div>
              <div>Timeline events: <span className="text-foreground">{fmt(data.timelineEvents)}</span></div>
              <div>Graph edges: <span className="text-foreground">{fmt(data.graphEdges)}</span></div>
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

/** -1 means "table missing" (V55 not applied yet) — surface that honestly rather than as 0. */
function fmt(v: number) {
  return v < 0 ? 'n/a' : v;
}
