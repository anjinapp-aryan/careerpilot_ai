import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Dialog, DialogHeader, DialogTitle, DialogBody } from '@/components/ui/dialog';
import { Tabs } from '@/components/ui/tabs';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import {
  getReview,
  getReviewHistory,
  parseReasons,
  type ReviewVerdict,
  type QualityCategory,
} from '@/lib/applicationReview';

const VERDICT_TONE: Record<ReviewVerdict, BadgeTone> = {
  READY: 'success',
  HUMAN_REVIEW: 'warning',
  BLOCKED: 'danger',
};

const QUALITY_TONE: Record<QualityCategory, BadgeTone> = {
  EXCELLENT: 'success',
  STRONG: 'success',
  GOOD: 'info',
  WEAK: 'warning',
  BLOCKED: 'danger',
};

const TABS = [
  { value: 'summary', label: 'Summary' },
  { value: 'resume', label: 'Resume Review' },
  { value: 'ats', label: 'ATS Review' },
  { value: 'company', label: 'Company Review' },
  { value: 'learning', label: 'Learning Review' },
  { value: 'consistency', label: 'Consistency' },
  { value: 'quality', label: 'Quality' },
  { value: 'history', label: 'History' },
];

/**
 * Phase 7.12 — read-only drawer over one AI Review. Reuses the existing Dialog + Tabs primitives (no
 * redesign). Ships dark: the endpoint 404s until the pipeline is enabled, so the drawer shows an empty
 * state rather than fabricating a review.
 */
export function ApplicationReviewDrawer({
  packageId,
  open,
  onOpenChange,
}: {
  packageId: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [tab, setTab] = useState('summary');

  const review = useQuery({
    queryKey: ['application-review', packageId],
    queryFn: () => getReview(packageId as string),
    enabled: open && !!packageId,
    retry: false,
  });
  const history = useQuery({
    queryKey: ['application-review', packageId, 'history'],
    queryFn: () => getReviewHistory(packageId as string),
    enabled: open && !!packageId && tab === 'history',
    retry: false,
  });

  const r = review.data;
  const byReviewer = (name: string) => parseReasons(r?.reasons).find((x) => x.reviewer === name);

  return (
    <Dialog open={open} onOpenChange={onOpenChange} size="lg">
      <DialogHeader>
        <DialogTitle>
          <span className="flex items-center gap-2">
            AI Review
            {r && <span className="text-sm font-normal text-muted-foreground">v{r.reviewVersion}</span>}
            {r && <Badge tone={VERDICT_TONE[r.verdict]}>{r.verdict}</Badge>}
            {r?.qualityCategory && <Badge tone={QUALITY_TONE[r.qualityCategory]}>{r.qualityCategory}</Badge>}
          </span>
        </DialogTitle>
      </DialogHeader>
      <Tabs items={TABS} value={tab} onChange={setTab} className="px-6" />
      <DialogBody>
        {review.isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : !r ? (
          <p className="text-sm text-muted-foreground">
            No AI review yet. The review pipeline may be disabled, or this package has not been reviewed.
          </p>
        ) : (
          <div className="space-y-3 text-sm">
            {tab === 'summary' && (
              <dl className="grid grid-cols-2 gap-2">
                <Field label="Verdict" value={r.verdict} />
                <Field label="Quality" value={`${r.qualityScore ?? '—'} (${r.qualityCategory ?? '—'})`} />
                <Field label="Confidence" value={r.confidence ?? '—'} />
                <Field label="Consistency" value={r.consistencyStatus ?? '—'} />
                <Field label="Resume" value={r.resumeScore ?? '—'} />
                <Field label="ATS" value={r.atsScore ?? '—'} />
                <Field label="Company fit" value={r.companyFitScore ?? '—'} />
                <Field label="Learning" value={r.learningConfidence ?? '—'} />
              </dl>
            )}
            {tab === 'resume' && <ReviewerBlock score={r.resumeScore} reason={byReviewer('resume')} />}
            {tab === 'ats' && <ReviewerBlock score={r.atsScore} reason={byReviewer('ats')} />}
            {tab === 'company' && <ReviewerBlock score={r.companyFitScore} reason={byReviewer('company_fit')} />}
            {tab === 'learning' && <ReviewerBlock score={r.learningConfidence} reason={byReviewer('learning')} />}
            {tab === 'consistency' && (
              <div className="space-y-1.5">
                <Badge tone={r.consistencyStatus === 'FAIL' ? 'danger' : r.consistencyStatus === 'WARNING' ? 'warning' : 'success'}>
                  {r.consistencyStatus ?? '—'}
                </Badge>
                <ReasonList items={byReviewer('consistency')?.reasons} />
              </div>
            )}
            {tab === 'quality' && (
              <dl className="grid grid-cols-2 gap-2">
                <Field label="Quality score" value={r.qualityScore ?? '—'} />
                <Field label="Category" value={r.qualityCategory ?? '—'} />
                <Field label="Confidence" value={r.confidence ?? '—'} />
                <Field label="Final verdict" value={r.verdict} />
              </dl>
            )}
            {tab === 'history' && (
              <div className="space-y-1.5">
                {history.isLoading ? (
                  <Skeleton className="h-24 w-full" />
                ) : !history.data || history.data.length === 0 ? (
                  <p className="text-muted-foreground">No prior reviews.</p>
                ) : (
                  history.data.map((h) => (
                    <div key={h.id} className="flex items-center justify-between rounded-md border border-border px-3 py-2">
                      <span>v{h.reviewVersion}</span>
                      <Badge tone={VERDICT_TONE[h.verdict]} className="text-[10px]">{h.verdict}</Badge>
                      <span className="text-xs text-muted-foreground">
                        Q{h.qualityScore ?? '—'} · {new Date(h.createdAt).toLocaleString()}
                      </span>
                    </div>
                  ))
                )}
              </div>
            )}
          </div>
        )}
      </DialogBody>
    </Dialog>
  );
}

function ReviewerBlock({ score, reason }: { score: number | null; reason?: { reasons?: string[] } }) {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <span className="text-xs text-muted-foreground">Score</span>
        <span className="text-lg font-semibold tabular-nums text-foreground">{score ?? '—'}</span>
      </div>
      <ReasonList items={reason?.reasons} />
    </div>
  );
}

function ReasonList({ items }: { items?: string[] }) {
  if (!items || items.length === 0) {
    return <p className="text-muted-foreground">No details recorded (reviewer disabled or nothing to report).</p>;
  }
  return (
    <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
      {items.map((it, i) => (
        <li key={i}>{it}</li>
      ))}
    </ul>
  );
}

function Field({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="font-medium text-foreground">{value}</dd>
    </div>
  );
}
