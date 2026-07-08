import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Dialog, DialogHeader, DialogTitle, DialogBody } from '@/components/ui/dialog';
import { Tabs } from '@/components/ui/tabs';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import {
  getPackage,
  getPackageHistory,
  getPackageValidation,
  parseChecks,
  type PackageValidationStatus,
} from '@/lib/applicationPackage';

const VALIDATION_TONE: Record<PackageValidationStatus, BadgeTone> = {
  READY: 'success',
  HUMAN_REVIEW: 'warning',
  BLOCKED: 'danger',
};

const TABS = [
  { value: 'summary', label: 'Summary' },
  { value: 'resume', label: 'Resume' },
  { value: 'ats', label: 'ATS' },
  { value: 'recommendation', label: 'Recommendation' },
  { value: 'company', label: 'Company' },
  { value: 'learning', label: 'Learning' },
  { value: 'validation', label: 'Validation' },
  { value: 'workflow', label: 'Workflow' },
  { value: 'history', label: 'History' },
];

/**
 * Phase 7.11 — read-only drawer over one Application Package. Reuses the existing Dialog + Tabs
 * primitives (no redesign). Ships dark: every endpoint 404s until the layer is enabled, so the drawer
 * shows an empty state rather than fabricating a package.
 */
export function ApplicationPackageDrawer({
  packageId,
  open,
  onOpenChange,
}: {
  packageId: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [tab, setTab] = useState('summary');

  const pkg = useQuery({
    queryKey: ['application-package', packageId],
    queryFn: () => getPackage(packageId as string),
    enabled: open && !!packageId,
    retry: false,
  });
  const validation = useQuery({
    queryKey: ['application-package', packageId, 'validation'],
    queryFn: () => getPackageValidation(packageId as string),
    enabled: open && !!packageId,
    retry: false,
  });
  const history = useQuery({
    queryKey: ['application-package', packageId, 'history'],
    queryFn: () => getPackageHistory(packageId as string),
    enabled: open && !!packageId && tab === 'history',
    retry: false,
  });

  const p = pkg.data;

  return (
    <Dialog open={open} onOpenChange={onOpenChange} size="lg">
      <DialogHeader>
        <DialogTitle>
          <span className="flex items-center gap-2">
            Application Package
            {p && <span className="text-sm font-normal text-muted-foreground">v{p.packageVersion}</span>}
            {p?.validationStatus && (
              <Badge tone={VALIDATION_TONE[p.validationStatus]}>{p.validationStatus}</Badge>
            )}
          </span>
        </DialogTitle>
      </DialogHeader>
      <Tabs items={TABS} value={tab} onChange={setTab} className="px-6" />
      <DialogBody>
        {pkg.isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : !p ? (
          <p className="text-sm text-muted-foreground">
            No package assembled yet. Package intelligence may be disabled, or nothing has been generated for this job.
          </p>
        ) : (
          <div className="space-y-3 text-sm">
            {tab === 'summary' && (
              <dl className="grid grid-cols-2 gap-2">
                <Field label="Status" value={p.status} />
                <Field label="Validation" value={p.validationStatus ?? 'PENDING'} />
                <Field label="Version" value={`v${p.packageVersion}`} />
                <Field label="Strength" value={p.recommendationStrength ?? '—'} />
                <Field label="Match" value={p.matchSummary ?? '—'} />
                <Field label="Learning boost" value={p.learningBoost ?? '—'} />
              </dl>
            )}
            {tab === 'resume' && (
              <dl className="grid grid-cols-2 gap-2">
                <Field label="Resume" value={ref(p.resumeId)} />
                <Field label="Tailored resume" value={ref(p.resumeTailoringId)} />
                <Field label="Cover letter" value={ref(p.coverLetterId)} />
              </dl>
            )}
            {tab === 'ats' && (
              <dl className="grid grid-cols-2 gap-2">
                <Field label="ATS analysis" value={ref(p.atsAnalysisId)} />
                <Field label="Gap analysis" value={ref(p.gapAnalysisId)} />
                <Field label="ATS explanation" value={ref(p.atsExplanationId)} />
              </dl>
            )}
            {tab === 'recommendation' && (
              <dl className="grid grid-cols-2 gap-2">
                <Field label="Recommendation audit" value={ref(p.recommendationAuditId)} />
                <Field label="Application decision" value={ref(p.applicationDecisionId)} />
                <Field label="Strength" value={p.recommendationStrength ?? '—'} />
              </dl>
            )}
            {tab === 'company' && (
              <p className="text-muted-foreground">
                {p.companyResearchAvailable
                  ? 'Company research snapshot bound to this package.'
                  : 'No company research bound (engine disabled or unavailable).'}
              </p>
            )}
            {tab === 'learning' && (
              <dl className="grid grid-cols-2 gap-2">
                <Field label="Learning boost" value={p.learningBoost ?? '—'} />
                <Field label="Match summary" value={p.matchSummary ?? '—'} />
              </dl>
            )}
            {tab === 'validation' && (
              <ValidationTab
                loading={validation.isLoading}
                checks={validation.data ? parseChecks(validation.data.checks) : []}
                status={validation.data?.status}
                reason={validation.data?.blockingReason ?? null}
              />
            )}
            {tab === 'workflow' && (
              <dl className="grid grid-cols-2 gap-2">
                <Field label="Correlation ID" value={ref(p.correlationId)} />
                <Field label="Application ID" value={ref(p.applicationId)} />
              </dl>
            )}
            {tab === 'history' && (
              <div className="space-y-1.5">
                {history.isLoading ? (
                  <Skeleton className="h-24 w-full" />
                ) : !history.data || history.data.length === 0 ? (
                  <p className="text-muted-foreground">No prior versions.</p>
                ) : (
                  history.data.map((v) => (
                    <div key={v.id} className="flex items-center justify-between rounded-md border border-border px-3 py-2">
                      <span>v{v.packageVersion}</span>
                      <Badge tone="neutral" className="text-[10px]">{v.status}</Badge>
                      <span className="text-xs text-muted-foreground">{new Date(v.createdAt).toLocaleString()}</span>
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

function ValidationTab({
  loading,
  checks,
  status,
  reason,
}: {
  loading: boolean;
  checks: ReturnType<typeof parseChecks>;
  status?: PackageValidationStatus;
  reason: string | null;
}) {
  if (loading) return <Skeleton className="h-24 w-full" />;
  if (!status) return <p className="text-muted-foreground">Not validated yet.</p>;
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <Badge tone={VALIDATION_TONE[status]}>{status}</Badge>
        {reason && <span className="text-xs text-muted-foreground">blocking: {reason}</span>}
      </div>
      <div className="space-y-1">
        {checks.map((c) => (
          <div key={c.name} className="flex items-center justify-between rounded-md border border-border px-3 py-1.5">
            <span>{c.name}</span>
            <Badge tone={c.passed ? 'success' : c.severity === 'BLOCK' ? 'danger' : 'warning'} className="text-[10px]">
              {c.passed ? 'pass' : c.severity}
            </Badge>
          </div>
        ))}
      </div>
    </div>
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

function ref(id: string | null): string {
  return id ? `${id.slice(0, 8)}…` : '—';
}
