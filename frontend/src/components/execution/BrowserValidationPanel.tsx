import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { AlertTriangle, Gauge, Globe, ScanSearch, ShieldAlert, ShieldCheck } from 'lucide-react';
import { api } from '@/lib/api';
import { Card } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { EmptyState } from '@/components/ui/empty-state';
import { KpiCard } from '@/components/dashboard/KpiCard';

/**
 * Phase 12C.5 / 13A — the only UI surface for the browser validation harness.
 *
 * It opens a real employer application page, discovers and classifies every control, produces an
 * automation plan and a confidence score, screenshots it, and stops. Nothing is uploaded, answered
 * or submitted — see the notice rendered at the top of the panel, which is deliberately always
 * visible rather than tucked into a tooltip.
 *
 * Lives on Operations Center (admin-only, already the home for automation observability) rather
 * than on a route of its own.
 */

interface FieldEntry {
  cssSelector: string;
  label?: string | null;
  name?: string | null;
  controlType?: string | null;
  canonicalField?: string | null;
  questionCategory?: string | null;
  required: boolean;
  visible: boolean;
  resolvable: boolean;
  unresolvedReason?: string | null;
}

interface Coverage {
  totalControls: number;
  fillableControls: number;
  supportedControls: number;
  unsupportedControls: number;
  unknownControls: number;
  requiredControls: number;
  mappedControls: number;
  missingRequiredValues: number;
}

interface Confidence {
  score: number;
  band: 'HIGH' | 'MEDIUM' | 'LOW';
  ready: boolean;
  rationale?: string | null;
}

interface PageEnvironment {
  spaFramework?: string | null;
  iframeCount: number;
  shadowRootCount: number;
  captchaDetected: boolean;
  cookieBannerDetected: boolean;
  consoleErrorCount: number;
  failedRequests: number;
  title?: string | null;
}

interface Drift {
  severity: 'NO_BASELINE' | 'NONE' | 'WARNING' | 'CRITICAL';
  alerting: boolean;
  baselineConfidence?: number | null;
  currentConfidence?: number | null;
  baselineRunCount: number;
  reasons: string[];
}

interface ValidationResponse {
  url: string;
  atsPlatform: string;
  status: 'COMPLETED' | 'FAILED' | 'REFUSED';
  message?: string | null;
  totalDurationMs: number;
  navigationDurationMs: number;
  discoveryDurationMs: number;
  planningDurationMs: number;
  selectorsDiscovered: number;
  coverage: Coverage;
  confidence: Confidence;
  environment: PageEnvironment;
  screenshotKey?: string | null;
  notes: string[];
  fields: FieldEntry[];
  drift?: Drift;
  submitted: boolean;
  documentsUploaded: boolean;
  questionsAnswered: boolean;
}

interface BrowserDiagnostics {
  enabled: boolean;
  health?: string;
  validation?: { validationAttempts?: number; validationCompleted?: number; validationFailed?: number };
  validationCampaign?: {
    enabled: boolean;
    note?: string;
    platforms?: Record<string, {
      pagesTested: number;
      distinctPostings?: number;
      averageConfidence?: number;
      ready?: boolean;
      note?: string;
    }>;
  };
}

function bandTone(band?: string): BadgeTone {
  if (band === 'HIGH') return 'success';
  if (band === 'MEDIUM') return 'warning';
  return 'danger';
}

function driftTone(severity?: string): BadgeTone {
  if (severity === 'CRITICAL') return 'danger';
  if (severity === 'WARNING') return 'warning';
  if (severity === 'NONE') return 'success';
  return 'neutral';
}

function fmtMs(ms?: number | null): string {
  if (ms == null) return '—';
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
}

export function BrowserValidationPanel() {
  const [url, setUrl] = useState('');
  const [result, setResult] = useState<ValidationResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const diagnostics = useQuery<BrowserDiagnostics>({
    queryKey: ['browser', 'diagnostics'],
    queryFn: async () => (await api.get('/api/diagnostics/browser')).data,
    retry: false,
    refetchInterval: 30_000,
  });

  const validate = useMutation({
    mutationFn: async (target: string) =>
      (await api.post<ValidationResponse>('/api/execution/validate-page', {
        url: target,
        resumeAvailable: true,
      })).data,
    onSuccess: (data) => {
      setResult(data);
      setError(null);
      void diagnostics.refetch();
    },
    onError: (e: unknown) => {
      // A refusal comes back as a 400 carrying the report, so surface the server's own reason
      // rather than a generic failure — "host is not a known ATS" is actionable, "request failed"
      // is not.
      const axiosErr = e as { response?: { data?: { message?: string } } };
      setError(axiosErr.response?.data?.message ?? 'Validation request failed.');
      setResult(null);
    },
  });

  const campaign = diagnostics.data?.validationCampaign;
  const platforms = campaign?.platforms ?? {};

  return (
    <div className="space-y-4">
      {/* Always visible, never a tooltip: the single most important fact about this panel. */}
      <Card className="border-l-4 border-l-emerald-500 p-4">
        <div className="flex items-start gap-3">
          <ShieldCheck className="mt-0.5 h-5 w-5 shrink-0 text-emerald-600" />
          <div className="space-y-1">
            <p className="text-sm font-semibold">This never submits an application.</p>
            <p className="text-sm text-muted-foreground">
              Validation opens the page, discovers and classifies every field, builds the automation
              plan and stops. No document is uploaded, no question answered, no submit clicked. A
              detected CAPTCHA is reported, never solved.
            </p>
          </div>
        </div>
      </Card>

      <Card className="space-y-3 p-4">
        <div className="space-y-1.5">
          <Label htmlFor="validate-url">Employer application URL</Label>
          <div className="flex gap-2">
            <Input
              id="validate-url"
              placeholder="https://job-boards.greenhouse.io/company/jobs/123456"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && url.trim() && !validate.isPending) validate.mutate(url.trim());
              }}
            />
            <Button
              onClick={() => validate.mutate(url.trim())}
              disabled={!url.trim() || validate.isPending}
            >
              <ScanSearch className="mr-2 h-4 w-4" />
              {validate.isPending ? 'Validating…' : 'Validate'}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            Only known public ATS hosts are permitted, and the resolved address is checked
            independently. A run typically takes 30–60 seconds — the page must fully render before
            anything is read.
          </p>
        </div>

        {error && (
          <div className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm">
            <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
            <span>{error}</span>
          </div>
        )}
      </Card>

      {validate.isPending && (
        <EmptyState
          icon={Globe}
          title="Driving a real browser"
          description="Launching Chromium, navigating, and waiting for the page to finish rendering before discovery runs."
        />
      )}

      {result && <ValidationResult result={result} />}

      {Object.keys(platforms).length > 0 && (
        <Card className="overflow-hidden">
          <div className="border-b border-border px-4 py-3">
            <h3 className="text-sm font-semibold">ATS validation campaign</h3>
            <p className="text-xs text-muted-foreground">
              Durable history. An untested ATS is absent rather than shown as zero.
            </p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-border bg-muted/30 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-2.5">ATS</th>
                  <th className="px-4 py-2.5">Runs</th>
                  <th className="px-4 py-2.5">Distinct postings</th>
                  <th className="px-4 py-2.5">Avg confidence</th>
                  <th className="px-4 py-2.5">Ready</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(platforms).map(([name, row]) => (
                  <tr key={name} className="border-b border-border/60 last:border-0">
                    <td className="px-4 py-2.5 font-medium">{name}</td>
                    <td className="px-4 py-2.5 tabular-nums">{row.pagesTested}</td>
                    <td className="px-4 py-2.5 tabular-nums">{row.distinctPostings ?? '—'}</td>
                    <td className="px-4 py-2.5 tabular-nums">
                      {row.averageConfidence != null ? `${row.averageConfidence}%` : (row.note ?? '—')}
                    </td>
                    <td className="px-4 py-2.5">
                      <Badge tone={row.ready ? 'success' : 'neutral'}>{row.ready ? 'READY' : 'NOT READY'}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}

function ValidationResult({ result }: { result: ValidationResponse }) {
  const { coverage, confidence, environment, drift } = result;

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard label="Controls discovered" value={coverage.totalControls} icon={ScanSearch} tone="primary" />
        <KpiCard label="Unidentified" value={coverage.unknownControls} icon={AlertTriangle} tone={coverage.unknownControls > 0 ? 'warning' : 'success'} />
        <KpiCard label="Required unfillable" value={coverage.missingRequiredValues} icon={ShieldAlert} tone={coverage.missingRequiredValues > 0 ? 'danger' : 'success'} />
        <KpiCard label="Confidence" value={`${confidence.score}%`} icon={Gauge} tone={confidence.ready ? 'success' : 'warning'} />
      </div>

      <Card className="space-y-3 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="info">{result.atsPlatform}</Badge>
          <Badge tone={result.status === 'COMPLETED' ? 'success' : 'danger'}>{result.status}</Badge>
          <Badge tone={bandTone(confidence.band)}>{confidence.band}</Badge>
          <Badge tone={confidence.ready ? 'success' : 'neutral'}>
            {confidence.ready ? 'READY' : 'NOT READY'}
          </Badge>
          {drift && (
            <Badge tone={driftTone(drift.severity)}>
              DRIFT: {drift.severity}
            </Badge>
          )}
          {environment.captchaDetected && <Badge tone="danger">CAPTCHA DETECTED</Badge>}
          {environment.spaFramework && <Badge tone="neutral">{environment.spaFramework}</Badge>}
        </div>

        {confidence.rationale && (
          <p className="text-sm text-muted-foreground">{confidence.rationale}</p>
        )}

        <div className="grid gap-2 text-xs text-muted-foreground sm:grid-cols-4">
          <span>Total {fmtMs(result.totalDurationMs)}</span>
          <span>Navigation {fmtMs(result.navigationDurationMs)}</span>
          <span>Discovery {fmtMs(result.discoveryDurationMs)}</span>
          <span>Planning {fmtMs(result.planningDurationMs)}</span>
        </div>

        {(environment.iframeCount > 0 || environment.shadowRootCount > 0) && (
          <p className="text-xs text-muted-foreground">
            {environment.iframeCount} iframe(s), {environment.shadowRootCount} shadow root(s) —
            discovery reads the top-level document only, so a form inside one is not visible to it.
          </p>
        )}

        {result.notes.length > 0 && (
          <ul className="space-y-1 text-xs text-muted-foreground">
            {result.notes.map((note, i) => (
              <li key={i} className="flex gap-1.5">
                <span aria-hidden>•</span>
                <span>{note}</span>
              </li>
            ))}
          </ul>
        )}

        {drift && drift.reasons.length > 0 && (
          <div className="rounded-md border border-border bg-muted/30 p-3">
            <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Selector drift
            </p>
            <ul className="space-y-1 text-sm">
              {drift.reasons.map((reason, i) => <li key={i}>{reason}</li>)}
            </ul>
          </div>
        )}
      </Card>

      <Card className="overflow-hidden">
        <div className="border-b border-border px-4 py-3">
          <h3 className="text-sm font-semibold">Discovered fields</h3>
          <p className="text-xs text-muted-foreground">
            Field identity and classification only — never the value that would be typed.
          </p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b border-border bg-muted/30 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
              <tr>
                <th className="px-4 py-2.5">Label</th>
                <th className="px-4 py-2.5">Control</th>
                <th className="px-4 py-2.5">Identified as</th>
                <th className="px-4 py-2.5">Req.</th>
                <th className="px-4 py-2.5">Resolvable</th>
                <th className="px-4 py-2.5">Reason if not</th>
              </tr>
            </thead>
            <tbody>
              {result.fields.map((field, i) => (
                <tr key={`${field.cssSelector}-${i}`} className="border-b border-border/60 last:border-0">
                  <td className="max-w-xs px-4 py-2.5">
                    <span className="line-clamp-2">{field.label || field.name || field.cssSelector}</span>
                  </td>
                  <td className="px-4 py-2.5 text-muted-foreground">{field.controlType ?? '—'}</td>
                  <td className="px-4 py-2.5">
                    <Badge tone={field.canonicalField === 'UNKNOWN' ? 'warning' : 'neutral'}>
                      {field.canonicalField ?? 'UNKNOWN'}
                    </Badge>
                  </td>
                  <td className="px-4 py-2.5">{field.required ? 'Yes' : '—'}</td>
                  <td className="px-4 py-2.5">
                    <Badge tone={field.resolvable ? 'success' : 'neutral'}>
                      {field.resolvable ? 'Yes' : 'No'}
                    </Badge>
                  </td>
                  <td className="max-w-sm px-4 py-2.5 text-xs text-muted-foreground">
                    <span className="line-clamp-2">{field.unresolvedReason ?? '—'}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
