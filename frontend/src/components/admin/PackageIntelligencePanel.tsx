import { useQuery } from '@tanstack/react-query';
import { PackageCheck } from 'lucide-react';
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

/** Phase 7.11 diagnostics — GET /api/diagnostics/package-intelligence. */
interface PackageDiagnostics {
  enabled: boolean;
  health: string;
  packageEnabled: boolean;
  validationEnabled: boolean;
  triggerEnabled: boolean;
  historyEnabled: boolean;
  diagnosticsEnabled: boolean;
  packagesGenerated: number;
  validationRuns: number;
  averageGenerationMs: number;
  executorQueueSize: number;
  executorQueueCapacity: number;
  failureCount: number;
  [k: string]: unknown;
}

function tone(v?: string | null): BadgeTone {
  return HEALTH_TONE[(v ?? '').toUpperCase()] ?? 'neutral';
}

const FLAGS: { key: keyof PackageDiagnostics; label: string }[] = [
  { key: 'packageEnabled', label: 'Assembly' },
  { key: 'validationEnabled', label: 'Validation' },
  { key: 'triggerEnabled', label: 'Auto trigger' },
  { key: 'historyEnabled', label: 'History' },
  { key: 'diagnosticsEnabled', label: 'Diagnostics' },
];

const VERDICTS: { key: string; label: string; tone: BadgeTone }[] = [
  { key: 'validation.READY', label: 'Ready', tone: 'success' },
  { key: 'validation.HUMAN_REVIEW', label: 'Human review', tone: 'warning' },
  { key: 'validation.BLOCKED', label: 'Blocked', tone: 'danger' },
];

/**
 * Phase 7.11 — Application Package Intelligence health. Reads the no-auth package diagnostics endpoint
 * (same convention as every other admin panel). Ships dark: with stock flags it reads NOT_CONFIGURED
 * and all counts are zero. No fabricated data — counts come straight from the backend.
 */
export function PackageIntelligencePanel() {
  const { data: d, isLoading } = useQuery<PackageDiagnostics>({
    queryKey: ['diagnostics', 'package-intelligence'],
    queryFn: async () => (await api.get('/api/diagnostics/package-intelligence')).data,
    retry: false,
  });

  const num = (k: string) => (typeof d?.[k] === 'number' ? (d[k] as number) : 0);

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle className="flex items-center gap-2 text-base">
          <PackageCheck className="h-4 w-4 text-muted-foreground" /> Application Package Intelligence (Phase 7.11)
        </CardTitle>
        {d && <Badge tone={tone(d.health)}>{d.health}</Badge>}
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-28 w-full" />
        ) : !d ? (
          <p className="text-sm text-muted-foreground">Package diagnostics unavailable.</p>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap gap-1.5">
              {FLAGS.map((f) => (
                <Badge key={f.label} tone={d[f.key] ? 'success' : 'neutral'} className="text-[10px]">
                  {f.label}: {d[f.key] ? 'on' : 'off'}
                </Badge>
              ))}
            </div>

            <div className="grid gap-3 sm:grid-cols-4">
              <Stat label="Generated" value={d.packagesGenerated} />
              <Stat label="Validation runs" value={d.validationRuns} />
              <Stat label="Avg gen (ms)" value={d.averageGenerationMs} />
              <Stat label="Queue" value={`${d.executorQueueSize}/${d.executorQueueCapacity}`} />
            </div>

            <div>
              <p className="mb-1.5 text-xs font-medium text-muted-foreground">Validation verdicts</p>
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
                Only READY packages are eligible for auto-apply — HUMAN_REVIEW and BLOCKED stop automated submission.
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
