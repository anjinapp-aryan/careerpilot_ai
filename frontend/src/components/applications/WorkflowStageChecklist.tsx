import { Check, Minus, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import type { ApplicationCard } from '@/types/workflow';

interface StageItem {
  label: string;
  /** true = present, false = confirmed absent, null = not tracked at per-application granularity. */
  present: boolean | null;
  tab: string;
}

/**
 * Generalizes the artifact-presence checklist pattern from
 * `components/submission/MySubmissionsPanel.tsx` ("Resume tailored: yes/—" rows) into a reusable
 * 10-item pipeline checklist. Each row is clickable — jumps the surrounding `ApplicationDrawer` to
 * the relevant tab. Items with no per-application signal (STAR Stories/Interview Prep/Salary
 * Intelligence/Submission — none of these persist a per-(user,job) boolean anywhere in the schema)
 * render a neutral dash rather than fabricating a status.
 */
export function WorkflowStageChecklist({ card, onNavigate }: { card: ApplicationCard; onNavigate: (tab: string) => void }) {
  const items: StageItem[] = [
    { label: 'Resume Intelligence', present: card.resumeTailored, tab: 'resume' },
    { label: 'ATS Optimization', present: card.atsAnalysisReady, tab: 'resume' },
    { label: 'Job Matching', present: card.matchScore != null, tab: 'overview' },
    { label: 'Cover Letter', present: card.coverLetterReady, tab: 'coverLetter' },
    { label: 'STAR Stories', present: null, tab: 'interview' },
    { label: 'Interview Prep', present: null, tab: 'interview' },
    { label: 'Salary Intelligence', present: null, tab: 'aiInsights' },
    { label: 'Application Package', present: card.applicationPackageReady, tab: 'documents' },
    { label: 'Submission', present: card.lifecycleStatus != null && card.lifecycleStatus !== 'DRAFT', tab: 'timeline' },
    { label: 'Tracking', present: card.lifecycleStatus != null, tab: 'timeline' },
  ];

  return (
    <div className="space-y-1.5">
      {items.map((item) => (
        <button
          key={item.label}
          type="button"
          onClick={() => onNavigate(item.tab)}
          className="flex w-full items-center justify-between rounded-lg border border-border bg-card px-3 py-2 text-left text-sm transition-colors hover:bg-muted/40"
        >
          <span className="text-foreground">{item.label}</span>
          <StageIcon present={item.present} />
        </button>
      ))}
    </div>
  );
}

function StageIcon({ present }: { present: boolean | null }) {
  if (present === null) {
    return <Minus className="h-4 w-4 text-muted-foreground/60" aria-label="Not tracked" />;
  }
  return present ? (
    <Check className={cn('h-4 w-4 text-success')} aria-label="Ready" />
  ) : (
    <X className="h-4 w-4 text-muted-foreground/60" aria-label="Not yet" />
  );
}
