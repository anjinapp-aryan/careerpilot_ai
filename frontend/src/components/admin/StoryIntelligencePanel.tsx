import { useQuery } from '@tanstack/react-query';
import { BookOpen } from 'lucide-react';
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

/** Phase 7.15 diagnostics — GET /api/diagnostics/story. */
interface StoryDiagnostics {
  enabled: boolean;
  health: string;
  extractionEnabled: boolean;
  generationEnabled: boolean;
  recommendationEnabled: boolean;
  analyticsEnabled: boolean;
  searchEnabled: boolean;
  historyEnabled: boolean;
  workerTriggerEnabled: boolean;
  storiesGenerated: number;
  storyVersions: number;
  usageRecords: number;
  recommendationCount: number;
  averageQualityScore: number | null;
  executorQueueSize: number;
  executorQueueCapacity: number;
  [k: string]: unknown;
}

const FLAGS: { key: keyof StoryDiagnostics; label: string }[] = [
  { key: 'extractionEnabled', label: 'Extraction' },
  { key: 'generationEnabled', label: 'Generation' },
  { key: 'recommendationEnabled', label: 'Recommendation' },
  { key: 'analyticsEnabled', label: 'Analytics' },
  { key: 'searchEnabled', label: 'Search' },
  { key: 'historyEnabled', label: 'History' },
  { key: 'workerTriggerEnabled', label: 'Auto-draft worker' },
];

/** Phase 7.15 — admin health panel for STAR Story Intelligence (counts-only diagnostics). */
export function StoryIntelligencePanel() {
  const { data, isLoading } = useQuery({
    queryKey: ['diagnostics', 'story'],
    queryFn: async () => (await api.get<StoryDiagnostics>('/api/diagnostics/story')).data,
    refetchInterval: 60_000,
    retry: false,
  });

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <BookOpen className="h-4 w-4 text-primary" />
          STAR story intelligence
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
              <div>Stories: <span className="text-foreground">{fmt(data.storiesGenerated)}</span></div>
              <div>Versions: <span className="text-foreground">{fmt(data.storyVersions)}</span></div>
              <div>Usage records: <span className="text-foreground">{fmt(data.usageRecords)}</span></div>
              <div>Recommendations: <span className="text-foreground">{fmt(data.recommendationCount)}</span></div>
              <div>Avg quality: <span className="text-foreground">{data.averageQualityScore ?? 'n/a'}</span></div>
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

/** -1 means "table missing" (V56 not applied yet) — surface that honestly rather than as 0. */
function fmt(v: number) {
  return v < 0 ? 'n/a' : v;
}
