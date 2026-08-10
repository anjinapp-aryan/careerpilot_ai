import { CheckCircle2, ExternalLink, FileCheck, Send, XCircle } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogBody, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import type { RecommendedJob } from '@/types/workflow';

interface PrepareApplicationDrawerProps {
  rec: RecommendedJob | null;
  onClose: () => void;
  /** Only reachable via the drawer's own "Apply now" button — never called on open/close. */
  onApply: (jobId: string) => void;
}

/**
 * Prepare Application — a read-only preview, deliberately separate from Apply.
 *
 * <p>This drawer consumes only data already present on the {@code RecommendedJob} the caller
 * already fetched (match score, matched/missing skills, employer URL, ATS) — no new API call, no
 * duplicate matching/scoring/ATS-detection logic. It exists to answer "am I ready, and where does
 * this actually come from" before the user decides to click Apply, not to replace or gate it.
 *
 * <p><b>Never calls {@code onApply} on its own.</b> The only path from this component to the
 * existing application-execution pipeline is the user explicitly clicking "Apply now" below —
 * opening or closing this drawer has no side effect on any application, execution, or submission
 * state. Full resume tailoring / cover-letter generation / application-package assembly remain
 * exactly where they already lived: inside the existing Apply pipeline, gated by its own existing
 * approval and browser-automation safety controls, untouched by this component.
 */
export function PrepareApplicationDrawer({ rec, onClose, onApply }: PrepareApplicationDrawerProps) {
  const job = rec?.job;
  const employerUrl = job ? job.sourceUrl || job.externalUrl || null : null;
  const atsLabel = rec?.atsPlatform
    ? rec.atsPlatform.charAt(0) + rec.atsPlatform.slice(1).toLowerCase()
    : null;

  return (
    <Dialog open={!!rec} onOpenChange={(o) => !o && onClose()} size="md">
      <DialogHeader onClose={onClose}>
        <DialogTitle>
          <span className="flex items-center gap-2">
            <FileCheck className="h-4 w-4 text-primary" /> Prepare Application
          </span>
        </DialogTitle>
        <DialogDescription>
          {job ? `${job.title} · ${job.company}` : 'Review this role before applying.'}
        </DialogDescription>
      </DialogHeader>
      {rec && job && (
        <DialogBody className="space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone="primary">Match {rec.matchScore}%</Badge>
            {atsLabel && <Badge tone="neutral">ATS: {atsLabel}</Badge>}
            {job.location && <Badge tone="neutral">{job.location}</Badge>}
          </div>

          {/* Why this job — reuses the exact matchedSkills/missingSkills already scored by the
              existing recommendation engine. Nothing here is recomputed or guessed. */}
          <div>
            <h4 className="mb-2 text-sm font-semibold text-foreground">Why this job matches</h4>
            {rec.matchedSkills.length === 0 && rec.missingSkills.length === 0 ? (
              <p className="text-sm text-muted-foreground">No skill-level match data available for this role.</p>
            ) : (
              <div className="space-y-1.5">
                {rec.matchedSkills.length > 0 && (
                  <p className="flex flex-wrap items-center gap-1.5 text-sm text-success">
                    {rec.matchedSkills.map((s) => (
                      <span key={s} className="flex items-center gap-1">
                        <CheckCircle2 className="h-3.5 w-3.5" /> {s}
                      </span>
                    ))}
                  </p>
                )}
                {rec.missingSkills.length > 0 && (
                  <>
                    <p className="mt-2 text-xs font-medium text-muted-foreground">Potential gaps</p>
                    <p className="flex flex-wrap items-center gap-1.5 text-sm text-warning">
                      {rec.missingSkills.map((s) => (
                        <span key={s} className="flex items-center gap-1">
                          <XCircle className="h-3.5 w-3.5" /> {s}
                        </span>
                      ))}
                    </p>
                  </>
                )}
              </div>
            )}
          </div>

          {/* Employer verification — same real URL as the card's own "View Employer Job" link. */}
          <div>
            <h4 className="mb-1 text-sm font-semibold text-foreground">Employer posting</h4>
            {employerUrl ? (
              <a
                href={employerUrl}
                target="_blank"
                rel="noopener noreferrer"
                aria-label={`View Employer Job — opens ${job.company}'s real posting in a new tab`}
                className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
              >
                View Employer Job <ExternalLink className="h-3.5 w-3.5" />
              </a>
            ) : (
              <p className="text-sm text-muted-foreground">Employer posting unavailable — no source URL was captured for this job.</p>
            )}
          </div>

          {/* Honest "what happens next" — no fabricated readiness score. Resume tailoring, cover
              letter generation and application-package assembly are real capabilities of the
              existing Apply pipeline; this text describes what that pipeline already does rather
              than duplicating it or pretending it already ran. */}
          <div className="rounded-lg border border-border bg-muted/30 p-3">
            <h4 className="mb-1 text-sm font-semibold text-foreground">What happens when you Apply</h4>
            <p className="text-sm text-muted-foreground">
              CareerPilot tailors your resume and prepares your application package for this specific role,
              then pauses for your review before anything is sent — a human approval step, every time. Nothing
              is submitted to the employer automatically, and Preparation itself never starts this process.
            </p>
          </div>

          <div className="flex justify-end gap-2 pt-1">
            <Button variant="outline" size="sm" onClick={onClose}>
              Cancel
            </Button>
            <Button size="sm" onClick={() => onApply(job.id)}>
              <Send className="h-3.5 w-3.5" /> Apply now
            </Button>
          </div>
        </DialogBody>
      )}
    </Dialog>
  );
}
