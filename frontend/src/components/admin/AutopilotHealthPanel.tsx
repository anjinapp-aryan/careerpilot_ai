import { useQuery } from '@tanstack/react-query';
import { Bot } from 'lucide-react';
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

/** Phase 7 autopilot diagnostics — GET /api/diagnostics/autopilot (+ /decision, /apply). */
interface AutopilotOverview {
  enabled: boolean;
  health: string;
  decisionEnabled: boolean;
  resumeSelectionEnabled: boolean;
  resumeAutopilotEnabled: boolean;
  autoApplyEnabled: boolean;
  companyResearchEnabled: boolean;
  interviewPrepEnabled: boolean;
  calendarEnabled: boolean;
  executorQueueSize: number;
  executorQueueCapacity: number;
  providers: string[];
  runs?: number;
  jobsProcessed?: number;
  autoApplied?: number;
  failures?: number;
}

interface AutopilotStage {
  health: string;
  totalDecisions?: number;
  totalSubmissions?: number;
  [k: string]: unknown;
}

function tone(v?: string | null): BadgeTone {
  return HEALTH_TONE[(v ?? '').toUpperCase()] ?? 'neutral';
}

const FLAGS: { key: keyof AutopilotOverview; label: string }[] = [
  { key: 'decisionEnabled', label: 'Decision' },
  { key: 'resumeSelectionEnabled', label: 'Resume selection' },
  { key: 'resumeAutopilotEnabled', label: 'Auto tailoring' },
  { key: 'autoApplyEnabled', label: 'Auto apply' },
  { key: 'companyResearchEnabled', label: 'Company research' },
  { key: 'interviewPrepEnabled', label: 'Interview prep' },
  { key: 'calendarEnabled', label: 'Calendar' },
];

/**
 * Phase 7 — Application Agent health. Reads the no-auth autopilot diagnostics endpoints (same
 * convention as every other admin panel). Ships dark: with stock flags every stage reads
 * NOT_CONFIGURED. No fabricated data — counts come straight from the backend.
 */
export function AutopilotHealthPanel() {
  const overview = useQuery<AutopilotOverview>({
    queryKey: ['diagnostics', 'autopilot'],
    queryFn: async () => (await api.get('/api/diagnostics/autopilot')).data,
    retry: false,
  });
  const decision = useQuery<AutopilotStage>({
    queryKey: ['diagnostics', 'autopilot', 'decision'],
    queryFn: async () => (await api.get('/api/diagnostics/autopilot/decision')).data,
    retry: false,
  });
  const apply = useQuery<AutopilotStage>({
    queryKey: ['diagnostics', 'autopilot', 'apply'],
    queryFn: async () => (await api.get('/api/diagnostics/autopilot/apply')).data,
    retry: false,
  });

  const o = overview.data;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle className="flex items-center gap-2 text-base">
          <Bot className="h-4 w-4 text-muted-foreground" /> Application Agent (Phase 7)
        </CardTitle>
        {o && <Badge tone={tone(o.health)}>{o.health}</Badge>}
      </CardHeader>
      <CardContent>
        {overview.isLoading ? (
          <Skeleton className="h-28 w-full" />
        ) : !o ? (
          <p className="text-sm text-muted-foreground">Autopilot diagnostics unavailable.</p>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap gap-1.5">
              {FLAGS.map((f) => (
                <Badge key={f.label} tone={o[f.key] ? 'success' : 'neutral'} className="text-[10px]">
                  {f.label}: {o[f.key] ? 'on' : 'off'}
                </Badge>
              ))}
            </div>

            <div className="grid gap-3 sm:grid-cols-4">
              <Stat label="Decisions" value={decision.data?.totalDecisions ?? 0} />
              <Stat label="Submissions" value={apply.data?.totalSubmissions ?? 0} />
              <Stat label="Auto applied" value={o.autoApplied ?? 0} />
              <Stat label="Queue" value={`${o.executorQueueSize}/${o.executorQueueCapacity}`} />
            </div>

            <div>
              <p className="mb-1.5 text-xs font-medium text-muted-foreground">Registered application providers</p>
              <div className="flex flex-wrap gap-1.5">
                {(o.providers ?? []).map((p) => (
                  <Badge key={p} tone="neutral" className="text-[10px]">{p}</Badge>
                ))}
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                No provider has automated submission wired — the agent routes every application to human review.
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
