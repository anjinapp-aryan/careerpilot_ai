import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, FileText, ScanLine, Sparkles, TrendingDown, XCircle } from 'lucide-react';
import { api } from '@/lib/api';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogBody, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import type { JobMatchExplanation, ScoreBreakdown } from '@/types/workflow';

interface ExplainDialogProps {
  jobId: string | null;
  jobTitle?: string;
  /** Per-factor sub-scores from the recommender (WHY THIS JOB). Nullable on the legacy path. */
  breakdown?: ScoreBreakdown | null;
  /** Overall match score, shown alongside the breakdown. */
  matchScore?: number | null;
  onClose: () => void;
}

/** Factor labels + relative weights, mirroring JobScoring.ScoreBreakdown on the backend. */
const SCORE_FACTORS: { key: keyof ScoreBreakdown; label: string; weight: number }[] = [
  { key: 'skills', label: 'Skills', weight: 35 },
  { key: 'experience', label: 'Experience', weight: 20 },
  { key: 'role', label: 'Role fit', weight: 15 },
  { key: 'location', label: 'Location', weight: 10 },
  { key: 'salary', label: 'Salary', weight: 10 },
  { key: 'visa', label: 'Visa', weight: 5 },
  { key: 'workMode', label: 'Work mode', weight: 5 },
];

function factorTone(v: number): string {
  if (v >= 70) return 'bg-success';
  if (v >= 40) return 'bg-warning';
  return 'bg-danger';
}

/** WHY THIS JOB — a horizontal bar per scoring factor. */
function ScoreBreakdownSection({ breakdown, matchScore }: { breakdown: ScoreBreakdown; matchScore?: number | null }) {
  return (
    <div>
      <h4 className="mb-2 flex items-center justify-between text-sm font-semibold text-foreground">
        <span className="flex items-center gap-1.5">
          <Sparkles className="h-4 w-4 text-primary" /> Why this job — score breakdown
        </span>
        {matchScore != null && <span className="tabular-nums text-muted-foreground">{matchScore}% overall</span>}
      </h4>
      <div className="space-y-2">
        {SCORE_FACTORS.map((f) => {
          const v = Math.max(0, Math.min(100, Math.round(breakdown[f.key] ?? 0)));
          return (
            <div key={f.key} className="flex items-center gap-3">
              <span className="w-24 shrink-0 text-xs text-muted-foreground">{f.label}</span>
              <div className="h-2 flex-1 overflow-hidden rounded-full bg-muted">
                <div className={`h-full rounded-full ${factorTone(v)}`} style={{ width: `${v}%` }} />
              </div>
              <span className="w-8 shrink-0 text-right text-xs font-medium tabular-nums text-foreground">{v}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** WHY NOT — the weakest factors, so the user sees what is holding the score back. */
function WhyNotSection({ breakdown }: { breakdown: ScoreBreakdown }) {
  const weak = SCORE_FACTORS
    .map((f) => ({ ...f, value: Math.round(breakdown[f.key] ?? 0) }))
    .filter((f) => f.value < 50)
    .sort((a, b) => a.value - b.value);
  if (weak.length === 0) return null;
  return (
    <div className="rounded-lg border border-warning/30 bg-warning/5 p-3">
      <h4 className="mb-1.5 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <TrendingDown className="h-4 w-4 text-warning" /> Why not — what's holding the score back
      </h4>
      <ul className="space-y-1 pl-1">
        {weak.map((f) => (
          <li key={f.key} className="flex gap-2 text-sm text-muted-foreground">
            <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-warning" />
            <span>
              <span className="font-medium text-foreground">{f.label}</span> scored {f.value}/100
              {f.weight >= 15 ? ' — a high-weight factor worth improving' : ''}.
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Section({
  icon: Icon,
  title,
  items,
  tone,
}: {
  icon: typeof CheckCircle2;
  title: string;
  items: string[];
  tone: string;
}) {
  if (!items.length) return null;
  return (
    <div>
      <h4 className="mb-1.5 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <Icon className={`h-4 w-4 ${tone}`} /> {title}
      </h4>
      <ul className="space-y-1 pl-1">
        {items.map((it, i) => (
          <li key={i} className="flex gap-2 text-sm text-muted-foreground">
            <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-muted-foreground" />
            {it}
          </li>
        ))}
      </ul>
    </div>
  );
}

export function ExplainDialog({ jobId, jobTitle, breakdown, matchScore, onClose }: ExplainDialogProps) {
  const { data, isLoading, isError } = useQuery<JobMatchExplanation>({
    queryKey: ['jobs', 'explain', jobId],
    queryFn: async () => (await api.post(`/api/jobs/${jobId}/explain`)).data,
    enabled: !!jobId,
    staleTime: Infinity, // result is cached server-side; no need to refetch
  });

  return (
    <Dialog open={!!jobId} onOpenChange={(o) => !o && onClose()} size="lg">
      <DialogHeader onClose={onClose}>
        <DialogTitle>
          <span className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-primary" /> Why am I a match?
          </span>
        </DialogTitle>
        <DialogDescription>{jobTitle ?? 'AI-generated fit analysis for this role.'}</DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-5">
        {isLoading ? (
          <div className="space-y-3">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-16 rounded-lg" />
            ))}
            <p className="text-center text-xs text-muted-foreground">Analyzing your fit…</p>
          </div>
        ) : isError || !data ? (
          <p className="text-sm text-muted-foreground">
            Could not generate an explanation right now. Please try again shortly.
          </p>
        ) : (
          <>
            {breakdown && <ScoreBreakdownSection breakdown={breakdown} matchScore={matchScore} />}
            {breakdown && <WhyNotSection breakdown={breakdown} />}
            <Section icon={CheckCircle2} title="Matching skills" items={data.matchingSkills} tone="text-success" />
            <Section icon={XCircle} title="Skills to build" items={data.missingSkills} tone="text-warning" />
            <Section icon={FileText} title="Resume improvements" items={data.resumeImprovements} tone="text-primary" />
            <Section icon={ScanLine} title="ATS improvements" items={data.atsImprovements} tone="text-secondary" />
            {data.modelUsed && (
              <p className="pt-1 text-right text-[11px] text-muted-foreground">Generated by {data.modelUsed}</p>
            )}
          </>
        )}
      </DialogBody>
    </Dialog>
  );
}
