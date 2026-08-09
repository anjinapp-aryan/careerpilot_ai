import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, ChevronDown, ChevronRight, FileText, Send } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogHeader, DialogTitle, DialogDescription, DialogBody, DialogFooter } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/input';
import { useToast } from '@/components/ui/toast';
import { SubmissionStepper } from './SubmissionStepper';
import { applicationSubmission } from '@/lib/applicationSubmission';
import type {
  ApplicationSubmissionSession,
  ApplicationSubmissionSessionDetail,
} from '@/types/workflow';

/** Status → badge tone. Terminal-good green, failed red, waiting amber, everything mid-flight neutral. */
const STATUS_TONE: Record<string, BadgeTone> = {
  COMPLETED: 'success',
  VERIFIED: 'success',
  SUBMITTED: 'info',
  TRACKING: 'info',
  SUBMITTING: 'info',
  WAITING_APPROVAL: 'warning',
  WAITING_MANUAL_SUBMISSION: 'warning',
  // Guided Apply — the candidate's own claim, never verified by CareerPilot. Same "warning" tone as
  // WAITING_MANUAL_SUBMISSION rather than "success": it is real progress, not a confirmed outcome.
  USER_REPORTED_SUBMITTED: 'warning',
  VERIFICATION_FAILED: 'warning',
  FAILED: 'danger',
};

/**
 * Explicit copy overrides for statuses where the honest wording matters — never let a
 * mechanically-generated label imply a real submission occurred when it didn't (see CLAUDE.md's
 * application-submission truthfulness discipline: WAITING_MANUAL_SUBMISSION means no ATS
 * connector/Playwright click ever happened, so it must never read as "submitted"/"complete").
 */
const STATUS_LABELS: Record<string, string> = {
  WAITING_MANUAL_SUBMISSION: 'Package ready — submit manually',
  PACKAGE_READY: 'Application package ready',
  USER_REPORTED_SUBMITTED: 'You reported this as submitted',
};

function toneFor(status: string): BadgeTone {
  return STATUS_TONE[status] ?? 'neutral';
}

function humanStatus(status: string): string {
  return STATUS_LABELS[status] ?? status.replaceAll('_', ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

function SubmissionRow({ session }: { session: ApplicationSubmissionSession }) {
  const [open, setOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [note, setNote] = useState('');
  const qc = useQueryClient();
  const { toast } = useToast();

  const reportSubmitted = useMutation({
    mutationFn: () => applicationSubmission.reportSubmitted(session.id, note || null),
    onSuccess: () => {
      toast({ variant: 'success', title: 'Marked as submitted' });
      setConfirmOpen(false);
      setNote('');
      qc.invalidateQueries({ queryKey: ['application-submission'] });
    },
    onError: () =>
      toast({ variant: 'error', title: 'Could not update', description: 'This application may no longer be awaiting manual submission.' }),
  });

  // Detail (generated answers) is fetched lazily, only when a row is expanded.
  const detail = useQuery<ApplicationSubmissionSessionDetail | null>({
    queryKey: ['application-submission', 'detail', session.id],
    queryFn: () => applicationSubmission.get(session.id),
    enabled: open,
    staleTime: 60_000,
    retry: false,
  });

  const answers = detail.data?.answers ?? [];

  return (
    <div className="rounded-lg border border-border bg-card">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-3 px-4 py-3 text-left"
      >
        {open ? (
          <ChevronDown className="h-4 w-4 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 text-muted-foreground" />
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-medium text-foreground">
              Job #{session.jobId.slice(0, 8)}
            </span>
            <Badge tone={toneFor(session.status)}>{humanStatus(session.status)}</Badge>
          </div>
          <div className="mt-0.5 text-xs text-muted-foreground">
            {session.provider ? `via ${session.provider}` : 'provider pending'}
            {' · '}
            {session.submissionMethod?.toLowerCase()}
            {session.completedAt
              ? ` · completed ${new Date(session.completedAt).toLocaleString()}`
              : ` · started ${new Date(session.createdAt).toLocaleString()}`}
          </div>
        </div>
      </button>

      {open && (
        <div className="border-t border-border px-4 py-3">
          <div className="mb-4">
            <SubmissionStepper session={session} />
          </div>

          {session.failureReason && (
            <p className="mb-3 rounded-md bg-danger/10 px-3 py-2 text-xs text-danger">
              {session.failureReason}
            </p>
          )}

          {session.status === 'WAITING_MANUAL_SUBMISSION' && (
            <>
              <p className="mb-3 rounded-md bg-warning/10 px-3 py-2 text-xs text-warning">
                Automated submission isn't available for this employer's application system, so nothing
                was sent yet. Your tailored resume, cover letter, and answers below are ready — use them
                to complete the application yourself on the company's site.
              </p>
              <Button
                size="sm"
                variant="outline"
                className="mb-3"
                onClick={(e) => {
                  e.stopPropagation();
                  setConfirmOpen(true);
                }}
              >
                <CheckCircle2 className="h-3.5 w-3.5" /> Mark as submitted
              </Button>
            </>
          )}

          {session.status === 'USER_REPORTED_SUBMITTED' && (
            <p className="mb-3 rounded-md bg-warning/10 px-3 py-2 text-xs text-warning">
              You reported this application as submitted
              {session.userReportedSubmittedAt && ` on ${new Date(session.userReportedSubmittedAt).toLocaleString()}`}.
              CareerPilot did not verify this itself — it's your own record.
              {session.userSubmissionNote && (
                <span className="mt-1 block text-muted-foreground">Note: {session.userSubmissionNote}</span>
              )}
            </p>
          )}

          <div className="mb-3 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-muted-foreground sm:grid-cols-3">
            <span>Resume tailored: {session.resumeTailoringId ? 'yes' : '—'}</span>
            <span>Package: {session.applicationPackageId ? 'yes' : '—'}</span>
            <span>AI review: {session.applicationReviewId ? 'yes' : '—'}</span>
            <span>Company brief: {session.companyKnowledgeId ? 'yes' : '—'}</span>
            <span>Landed in board: {session.applicationId ? 'yes' : '—'}</span>
          </div>

          <div className="flex items-center gap-1.5 text-xs font-semibold text-foreground">
            <FileText className="h-3.5 w-3.5" />
            Generated answers {answers.length > 0 && `(${answers.length})`}
          </div>

          {detail.isLoading ? (
            <p className="mt-2 text-xs text-muted-foreground">Loading answers…</p>
          ) : answers.length === 0 ? (
            <p className="mt-2 text-xs text-muted-foreground">No generated answers for this session.</p>
          ) : (
            <ul className="mt-2 space-y-2">
              {answers.map((a) => (
                <li key={a.id} className="rounded-md bg-muted/40 px-3 py-2">
                  <div className="text-xs font-medium text-foreground">{a.questionText}</div>
                  <div className="mt-1 whitespace-pre-wrap text-xs text-muted-foreground">
                    {a.answerText || '—'}
                  </div>
                  {a.sourceRefs && (
                    <div className="mt-1 text-[11px] text-muted-foreground/70">Sources: {a.sourceRefs}</div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen} size="sm">
        <DialogHeader onClose={() => setConfirmOpen(false)}>
          <DialogTitle>Did you submit this application?</DialogTitle>
          <DialogDescription>
            CareerPilot cannot verify an employer's site directly — this records your own confirmation,
            not a system-verified submission.
          </DialogDescription>
        </DialogHeader>
        <DialogBody>
          <Textarea
            rows={3}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="Optional note — e.g. a confirmation number or reference ID"
          />
        </DialogBody>
        <DialogFooter>
          <Button variant="ghost" onClick={() => setConfirmOpen(false)}>
            Not yet
          </Button>
          <Button onClick={() => reportSubmitted.mutate()} loading={reportSubmitted.isPending}>
            Yes, I submitted it
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

/**
 * Phase 7.16 — "My submissions": the visible landing surface for the Application Submission
 * Pipeline. Reads GET /api/application-submission (the current user's sessions) and lets each be
 * expanded to see its generated interview answers, artifact readiness, and final status. Ships
 * DARK — the endpoint returns an empty list when `application.submission.enabled` is off, so this
 * panel renders nothing at all on a stock stack (appears only once there's a real submission).
 */
export function MySubmissionsPanel() {
  const sessions = useQuery<ApplicationSubmissionSession[]>({
    queryKey: ['application-submission', 'mine'],
    queryFn: () => applicationSubmission.list(),
    retry: false,
    staleTime: 30_000,
    refetchInterval: 15_000, // sessions advance async over ~1-2 min; poll so status updates without a manual refresh
  });

  const list = sessions.data ?? [];
  if (list.length === 0) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Send className="h-4 w-4" /> My submissions
          <Badge tone="neutral">{list.length}</Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {list.map((s) => (
          <SubmissionRow key={s.id} session={s} />
        ))}
      </CardContent>
    </Card>
  );
}
