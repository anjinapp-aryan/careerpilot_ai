import { useState } from 'react';
import { Check, ChevronDown, ChevronUp, Clipboard } from 'lucide-react';
import { STAGE_DETAIL_CONFIG } from './stageDetailConfig';
import { cn } from '@/lib/cn';
import type { AgentExecutionEntry } from '@/types/workflow';

function SectionHeading({ children }: { children: React.ReactNode }) {
  return (
    <h4 className="mb-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">{children}</h4>
  );
}

function BulletList({ items }: { items: string[] }) {
  if (!items.length) return null;
  return (
    <ul className="list-inside list-disc space-y-1 text-sm text-foreground">
      {items.map((item, i) => (
        <li key={i}>{item}</li>
      ))}
    </ul>
  );
}

function ScoreRow({ scores }: { scores: { label: string; value: number | null }[] }) {
  const withValues = scores.filter((s) => s.value != null);
  if (!withValues.length) return null;
  return (
    <div className="flex flex-wrap gap-4">
      {withValues.map((s) => (
        <div key={s.label} className="flex flex-col">
          <span className="text-base font-semibold tabular-nums text-foreground">{s.value}</span>
          <span className="text-[10px] uppercase tracking-wide text-muted-foreground">{s.label}</span>
        </div>
      ))}
    </div>
  );
}

function RawOutput({ payload }: { payload: Record<string, unknown> }) {
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const json = JSON.stringify(payload, null, 2);

  if (Object.keys(payload).length === 0) return null;

  return (
    <div className="rounded-lg border border-border">
      <button
        type="button"
        className="flex w-full items-center justify-between px-3 py-2 text-xs font-medium text-foreground"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        Raw output
        {open ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
      </button>
      {open && (
        <div className="border-t border-border">
          <div className="flex justify-end px-3 pt-2">
            <button
              type="button"
              className="flex items-center gap-1 rounded px-2 py-1 text-[11px] text-muted-foreground hover:text-foreground"
              onClick={async () => {
                await navigator.clipboard.writeText(json);
                setCopied(true);
                setTimeout(() => setCopied(false), 1500);
              }}
            >
              {copied ? <Check className="h-3 w-3" /> : <Clipboard className="h-3 w-3" />}
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
          <pre className="max-h-72 overflow-auto px-3 pb-3 text-[11px] leading-relaxed text-muted-foreground">
            {json}
          </pre>
        </div>
      )}
    </div>
  );
}

function ExecutionMetadata({ entry }: { entry: AgentExecutionEntry }) {
  return (
    <dl className="grid gap-x-6 gap-y-1 text-xs text-muted-foreground sm:grid-cols-2">
      {entry.started_at && (
        <div className="flex gap-1.5">
          <dt className="font-medium text-foreground">Started:</dt>
          <dd>{entry.started_at}</dd>
        </div>
      )}
      {entry.completed_at && (
        <div className="flex gap-1.5">
          <dt className="font-medium text-foreground">Completed:</dt>
          <dd>{entry.completed_at}</dd>
        </div>
      )}
      {entry.duration_ms != null && (
        <div className="flex gap-1.5">
          <dt className="font-medium text-foreground">Duration:</dt>
          <dd>{entry.duration_ms}ms</dd>
        </div>
      )}
      {entry.provider && (
        <div className="flex gap-1.5">
          <dt className="font-medium text-foreground">Provider:</dt>
          <dd className="uppercase">{entry.provider}</dd>
        </div>
      )}
      {entry.error && (
        <div className="flex gap-1.5 sm:col-span-2">
          <dt className="font-medium text-danger">Error:</dt>
          <dd className="text-danger">{entry.error}</dd>
        </div>
      )}
    </dl>
  );
}

export function StageDetailPanel({
  stageName,
  state,
  className,
}: {
  stageName: string;
  state: Record<string, unknown> | undefined;
  className?: string;
}) {
  const data = state ?? {};
  const config = STAGE_DETAIL_CONFIG[stageName];

  const summary = config?.summary?.(data) ?? null;
  const insights = config?.insights?.(data) ?? [];
  const scores = config?.scores?.(data) ?? [];
  const recommendations = config?.recommendations?.(data) ?? [];
  const rawKeys = config?.rawKeys ?? [];
  const rawPayload = Object.fromEntries(rawKeys.filter((k) => k in data).map((k) => [k, data[k]]));

  const executionEntries = Array.isArray(data.agent_execution) ? (data.agent_execution as AgentExecutionEntry[]) : [];
  const execution = executionEntries.find((e) => e?.name === stageName);

  const hasScores = scores.some((s) => s.value != null);
  const hasAnything = summary || insights.length || hasScores || recommendations.length || Object.keys(rawPayload).length || execution;

  if (!hasAnything) {
    return (
      <div className={cn('px-1 py-2 text-sm text-muted-foreground', className)}>
        No detailed output recorded for this stage yet.
      </div>
    );
  }

  return (
    <div className={cn('space-y-4 px-1 py-2', className)}>
      {summary && (
        <div>
          <SectionHeading>Executive Summary</SectionHeading>
          <p className="text-sm text-foreground">{summary}</p>
        </div>
      )}
      {insights.length > 0 && (
        <div>
          <SectionHeading>Key Insights</SectionHeading>
          <BulletList items={insights} />
        </div>
      )}
      {hasScores && (
        <div>
          <SectionHeading>Scores</SectionHeading>
          <ScoreRow scores={scores} />
        </div>
      )}
      {recommendations.length > 0 && (
        <div>
          <SectionHeading>Recommendations</SectionHeading>
          <BulletList items={recommendations} />
        </div>
      )}
      {Object.keys(rawPayload).length > 0 && <RawOutput payload={rawPayload} />}
      {execution && (
        <div>
          <SectionHeading>Execution Metadata</SectionHeading>
          <ExecutionMetadata entry={execution} />
        </div>
      )}
    </div>
  );
}
