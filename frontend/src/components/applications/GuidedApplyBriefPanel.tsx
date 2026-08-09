import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, Check, Copy, ExternalLink, ShieldAlert } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { guidedApply } from '@/lib/guidedApply';
import { blockerExplanation } from '@/lib/guidedApplyReasons';
import { ExecutionEvidenceSummary } from './ExecutionEvidenceSummary';
import type { ApplicationCard, GuidedApplyRecommendedAnswer } from '@/types/workflow';

const CHECKLIST_STEPS = [
  'Open employer application',
  'Confirm job title',
  'Upload resume',
  'Enter contact information',
  'Answer application questions',
  'Review answers',
  'Submit',
];

function checklistKey(applicationId: string) {
  return `guided-apply-checklist:${applicationId}`;
}

/**
 * Final Hardening Pass — Job.externalUrl is external, provider-supplied data (job-discovery
 * scrapers/APIs), not something this frontend can treat as trusted. `window.open` with a
 * `javascript:`/`data:`/other non-http(s) scheme would execute in this page's context. Fails
 * closed: a URL that doesn't parse, or doesn't parse to http/https, is treated exactly like a
 * missing URL — never opened, never rendered as a CTA.
 */
function isSafeExternalUrl(url: string | null | undefined): url is string {
  if (!url) return false;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

/**
 * Guided Apply checklist progress. Deliberately localStorage rather than a new backend table or
 * column: this is per-device UI progress tracking, not a fact CareerPilot needs to reason about —
 * see the Guided Apply architecture audit's "no unnecessary database duplication" rule.
 */
function loadChecklist(applicationId: string): Record<number, boolean> {
  try {
    return JSON.parse(localStorage.getItem(checklistKey(applicationId)) ?? '{}');
  } catch {
    return {};
  }
}

/**
 * Guided Apply — "CareerPilot prepares" panel: why automation stopped, the employer link, the
 * candidate's own application profile, recommended answers (never fabricated), and a checklist.
 * Reads `GET /api/applications/{id}/guided-apply-brief`; `card` supplies the job/employer/blocker
 * fields already joined by `ApplicationCardService`.
 */
export function GuidedApplyBriefPanel({ applicationId, card }: { applicationId: string; card: ApplicationCard }) {
  const { toast } = useToast();
  const [checked, setChecked] = useState<Record<number, boolean>>(() => loadChecklist(applicationId));
  const safeExternalUrl = isSafeExternalUrl(card.externalUrl) ? card.externalUrl : null;

  const brief = useQuery({
    queryKey: ['guided-apply-brief', applicationId],
    queryFn: () => guidedApply.brief(applicationId),
    staleTime: 60_000,
  });

  const toggle = (i: number) => {
    const next = { ...checked, [i]: !checked[i] };
    setChecked(next);
    localStorage.setItem(checklistKey(applicationId), JSON.stringify(next));
  };

  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      toast({ variant: 'success', title: 'Copied' });
    } catch {
      toast({ variant: 'error', title: 'Could not copy — your browser blocked clipboard access' });
    }
  };

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-warning/30 bg-warning/10 p-4">
        <div className="flex items-start gap-2">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
          <div>
            <p className="text-sm font-semibold text-foreground">Manual completion required</p>
            <p className="mt-1 text-xs text-muted-foreground">{blockerExplanation(card.blockerReason)}</p>
            <p className="mt-1 text-xs text-muted-foreground/80">
              We've prepared your application information below so you can complete the employer's form
              yourself. Estimated effort: 3–5 minutes.
            </p>
          </div>
        </div>
      </div>

      <ExecutionEvidenceSummary applicationId={applicationId} />

      {safeExternalUrl ? (
        <Button
          size="lg"
          className="w-full"
          aria-label="Open employer application in a new tab"
          onClick={() => window.open(safeExternalUrl, '_blank', 'noopener,noreferrer')}
        >
          <ExternalLink className="h-4 w-4" /> Open Employer Application
        </Button>
      ) : (
        <p className="rounded-lg border border-dashed border-border bg-muted/20 p-3 text-xs text-muted-foreground">
          Employer application link unavailable for this job.
        </p>
      )}

      {card.employerJobId && (
        <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-xs text-muted-foreground">
          <div>
            <dt className="font-medium text-foreground">Employer Job ID</dt>
            <dd>{card.employerJobId}</dd>
          </div>
        </dl>
      )}

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">Application Profile</h4>
        {brief.isLoading ? (
          <p className="text-xs text-muted-foreground">Loading…</p>
        ) : (
          <dl className="grid grid-cols-2 gap-x-6 gap-y-2 rounded-lg border border-border bg-muted/20 p-3 text-xs">
            {(brief.data?.profile.length ?? 0) === 0 && (
              <div className="col-span-2 text-muted-foreground">No verified profile facts available yet.</div>
            )}
            {(brief.data?.profile ?? []).map((f) => (
              <div key={f.label}>
                <dt className="font-medium text-foreground">{f.label}</dt>
                <dd className="text-muted-foreground">{f.value}</dd>
              </div>
            ))}
            {!brief.data?.resumeFilename && (
              <div>
                <dt className="font-medium text-foreground">Resume</dt>
                <dd className="text-muted-foreground">Resume not available</dd>
              </div>
            )}
          </dl>
        )}
      </div>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">Recommended Answers</h4>
        {brief.isLoading ? (
          <p className="text-xs text-muted-foreground">Loading…</p>
        ) : (brief.data?.recommendedAnswers.length ?? 0) === 0 ? (
          <p className="text-xs text-muted-foreground">No recommended answers available.</p>
        ) : (
          <div className="space-y-2">
            {brief.data!.recommendedAnswers.map((a) => (
              <RecommendedAnswerRow key={a.canonicalField} answer={a} onCopy={copy} />
            ))}
          </div>
        )}
      </div>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">Application Checklist</h4>
        <ul className="space-y-1">
          {CHECKLIST_STEPS.map((step, i) => (
            <li key={step}>
              <button
                type="button"
                onClick={() => toggle(i)}
                className="flex w-full items-center gap-2 rounded-md px-2 py-1 text-left text-sm hover:bg-muted/40"
              >
                <span
                  className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full border ${
                    checked[i] ? 'border-success bg-success text-white' : 'border-border'
                  }`}
                >
                  {checked[i] && <Check className="h-3 w-3" />}
                </span>
                <span className={checked[i] ? 'text-muted-foreground line-through' : 'text-foreground'}>{step}</span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function RecommendedAnswerRow({
  answer,
  onCopy,
}: {
  answer: GuidedApplyRecommendedAnswer;
  onCopy: (value: string) => void;
}) {
  if (answer.needsUserInput) {
    return (
      <div className="rounded-lg border border-dashed border-border bg-muted/10 p-3">
        <p className="text-xs font-medium text-foreground">{answer.question}</p>
        <p className="mt-1 flex items-center gap-1 text-xs text-warning">
          <AlertTriangle className="h-3 w-3" /> Needs your input
        </p>
      </div>
    );
  }
  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <p className="text-xs font-medium text-foreground">{answer.question}</p>
      <p className="mt-1 text-sm text-foreground">{answer.value}</p>
      <div className="mt-1.5 flex items-center justify-between gap-2 text-[11px] text-muted-foreground">
        <span className="truncate">
          Source: {answer.source} · Confidence: {answer.confidence}
        </span>
        <Button size="sm" variant="outline" onClick={() => onCopy(answer.value ?? '')}>
          <Copy className="h-3 w-3" /> Copy
        </Button>
      </div>
    </div>
  );
}
