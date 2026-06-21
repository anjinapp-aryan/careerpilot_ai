import type { WorkflowAgent, WorkflowRun } from '@/types/workflow';

/** Compact human label for a millisecond duration (e.g. "1.4s", "820ms", "2m 5s"). */
function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.round((ms % 60_000) / 1000);
  return `${m}m ${s}s`;
}

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col">
      <span className="text-sm font-semibold tabular-nums text-foreground">{value}</span>
      <span className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</span>
    </div>
  );
}

/**
 * Compact "Workflow Insights" strip — only ever shows metrics with real backing
 * data (total duration, recent-run success rate, slowest/fastest stage, total
 * cost). No fabricated metrics like "most expensive agent" or token usage, since
 * the agent-service doesn't track per-stage cost or token counts.
 */
export function WorkflowInsights({
  agents,
  allRuns,
  costUsd,
}: {
  agents: WorkflowAgent[];
  allRuns: WorkflowRun[];
  costUsd?: number | null;
}) {
  const totalDurationMs = agents.reduce((sum, a) => sum + (a.durationMs ?? 0), 0);

  const successRate =
    allRuns.length > 0
      ? Math.round((allRuns.filter((r) => r.status === 'COMPLETED').length / allRuns.length) * 100)
      : null;

  const timed = agents.filter((a): a is WorkflowAgent & { durationMs: number } => a.durationMs != null);
  const sorted = [...timed].sort((a, b) => a.durationMs - b.durationMs);
  const fastest = sorted.length >= 2 ? sorted[0] : null;
  const slowest = sorted.length >= 2 ? sorted[sorted.length - 1] : null;

  const tiles: { label: string; value: string }[] = [];
  if (totalDurationMs > 0) tiles.push({ label: 'Total Duration', value: formatDuration(totalDurationMs) });
  if (successRate != null) tiles.push({ label: 'Recent Run Success Rate', value: `${successRate}%` });
  if (fastest) tiles.push({ label: 'Fastest Stage', value: `${fastest.name} (${formatDuration(fastest.durationMs)})` });
  if (slowest) tiles.push({ label: 'Slowest Stage', value: `${slowest.name} (${formatDuration(slowest.durationMs)})` });
  if (costUsd != null) tiles.push({ label: 'Total Cost', value: `$${costUsd.toFixed(4)}` });

  if (tiles.length === 0) return null;

  return (
    <div className="mt-4 flex flex-wrap gap-6 border-t border-border pt-4">
      {tiles.map((t) => (
        <Tile key={t.label} label={t.label} value={t.value} />
      ))}
    </div>
  );
}
