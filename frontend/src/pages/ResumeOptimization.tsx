import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft,
  Briefcase,
  CheckCircle2,
  Download,
  FileText,
  History,
  Sparkles,
  Target,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Label, Textarea } from '@/components/ui/input';
import { EmptyState } from '@/components/ui/empty-state';
import { useToast } from '@/components/ui/toast';
import { WorkflowStatusStepper } from '@/components/workflow/WorkflowStatusStepper';
import { useWorkflowStatus } from '@/hooks/useWorkflowStatus';
import { cn } from '@/lib/cn';
import type { Job, OptimizationMode, Resume, ResumeVersion, WorkflowRun } from '@/types/workflow';

interface ModeOption {
  value: OptimizationMode;
  label: string;
  description: string;
}

const MODES: ModeOption[] = [
  { value: 'generic_ats', label: 'Generic ATS', description: 'Role-agnostic ATS optimization' },
  { value: 'senior_java_developer', label: 'Senior Java Developer', description: 'Backend Java / Spring Boot focus' },
  { value: 'java_architect', label: 'Java Architect', description: 'Distributed systems & design' },
  { value: 'solution_architect', label: 'Solution Architect', description: 'End-to-end cloud solutions' },
  { value: 'enterprise_architect', label: 'Enterprise Architect', description: 'Strategy & governance' },
  { value: 'engineering_manager', label: 'Engineering Manager', description: 'People & delivery leadership' },
  { value: 'upload_jd', label: 'Upload Job Description', description: 'Paste a specific JD' },
  { value: 'select_job', label: 'Select Existing Job', description: 'Target a saved CareerPilot job' },
];

function asStringList(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
}

function asNumber(v: unknown): number | null {
  return typeof v === 'number' ? v : null;
}

export default function ResumeOptimization() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const { toast } = useToast();

  const [mode, setMode] = useState<OptimizationMode>('generic_ats');
  const [jdText, setJdText] = useState('');
  const [selectedJobId, setSelectedJobId] = useState('');
  const [threadId, setThreadId] = useState<string | null>(null);

  const { data: resumes = [] } = useQuery<Resume[]>({
    queryKey: ['resumes'],
    queryFn: async () => (await api.get('/api/resumes')).data,
  });
  const resume = resumes.find((r) => r.id === id);

  // Jobs are only needed for the "select existing job" mode.
  const { data: jobsPage } = useQuery<{ content: Job[] }>({
    queryKey: ['jobs', 'all-for-optimize'],
    queryFn: async () => (await api.get('/api/jobs', { params: { size: 100 } })).data,
    enabled: mode === 'select_job',
  });
  const jobs = jobsPage?.content ?? [];

  const { data: versions = [] } = useQuery<ResumeVersion[]>({
    queryKey: ['resume-versions', id],
    queryFn: async () => (await api.get(`/api/resumes/${id}/versions`)).data,
  });

  const status = useWorkflowStatus(threadId);
  const run = status.data;

  const start = useMutation({
    mutationFn: async () => {
      const payload: Record<string, unknown> = {
        resumeId: id,
        jobIds: [],
        workflowType: 'RESUME_OPTIMIZATION',
        optimizationMode: mode,
      };
      if (mode === 'upload_jd') payload.jobDescriptionText = jdText;
      if (mode === 'select_job') payload.jobId = selectedJobId;
      const { data } = await api.post('/api/workflows/run', payload);
      return data as WorkflowRun;
    },
    onSuccess: (data) => {
      setThreadId(data.threadId);
      qc.invalidateQueries({ queryKey: ['workflows'] });
      toast({ variant: 'success', title: 'Optimization started', description: 'The AI is analyzing your resume.' });
    },
    onError: () => toast({ variant: 'error', title: 'Could not start', description: 'Please try again.' }),
  });

  const decide = useMutation({
    mutationFn: async (decision: 'approved' | 'rejected') =>
      api.post(`/api/workflows/${threadId}/resume`, { decision }),
    onSuccess: (_d, decision) => {
      qc.invalidateQueries({ queryKey: ['workflow-status', threadId] });
      qc.invalidateQueries({ queryKey: ['resume-versions', id] });
      qc.invalidateQueries({ queryKey: ['resumes'] });
      toast({
        variant: decision === 'approved' ? 'success' : 'default',
        title: decision === 'approved' ? 'Approved' : 'Rejected',
        description: decision === 'approved' ? 'Generating your optimized resume…' : 'Optimization discarded.',
      });
    },
  });

  async function downloadVersion(v: ResumeVersion, format: 'docx' | 'txt') {
    try {
      const res = await api.get(`/api/resumes/${id}/versions/${v.id}/download`, {
        params: { format },
        responseType: 'blob',
      });
      const url = URL.createObjectURL(res.data as Blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `optimized-v${v.versionNumber}.${format}`;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      toast({ variant: 'error', title: 'Download failed', description: 'Please try again.' });
    }
  }

  const canStart =
    !!id && !start.isPending && (mode !== 'upload_jd' || jdText.trim().length > 20) && (mode !== 'select_job' || !!selectedJobId);

  const state = (run?.state ?? {}) as Record<string, unknown>;
  const atsScore = asNumber(state.ats_score);
  const missingKeywords = asStringList(state.missing_keywords);
  const atsPlan = asStringList(state.ats_optimization_plan);
  const optimized = (state.optimized_resume ?? {}) as Record<string, unknown>;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Resume Optimization Center"
        description={resume ? resume.filename : 'AI-powered, ATS-optimized resume rewriting'}
        actions={
          <Link to="/resumes">
            <Button variant="outline">
              <ArrowLeft className="h-4 w-4" /> Back to library
            </Button>
          </Link>
        }
      />

      {!resume && resumes.length > 0 && (
        <EmptyState icon={FileText} title="Resume not found" description="It may have been deleted. Return to your library." />
      )}

      {/* Configure phase */}
      {!threadId && (
        <Card className="p-6">
          <h2 className="mb-1 flex items-center gap-2 text-sm font-semibold text-foreground">
            <Target className="h-4 w-4" /> Choose an optimization target
          </h2>
          <p className="mb-4 text-sm text-muted-foreground">
            We&apos;ll analyze ATS compatibility against this target, then rewrite your resume after you review.
          </p>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {MODES.map((m) => (
              <button
                key={m.value}
                type="button"
                onClick={() => setMode(m.value)}
                className={cn(
                  'rounded-xl border p-4 text-left transition-colors',
                  mode === m.value
                    ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                    : 'border-border hover:border-primary/40',
                )}
              >
                <p className="text-sm font-medium text-foreground">{m.label}</p>
                <p className="mt-0.5 text-xs text-muted-foreground">{m.description}</p>
              </button>
            ))}
          </div>

          {mode === 'upload_jd' && (
            <div className="mt-4 space-y-1.5">
              <Label htmlFor="jd">Job description</Label>
              <Textarea
                id="jd"
                rows={6}
                value={jdText}
                onChange={(e) => setJdText(e.target.value)}
                placeholder="Paste the full job description here…"
              />
            </div>
          )}

          {mode === 'select_job' && (
            <div className="mt-4 space-y-1.5">
              <Label htmlFor="job">Target job</Label>
              {jobs.length === 0 ? (
                <p className="text-sm text-muted-foreground">No saved jobs yet — add one on the Jobs page first.</p>
              ) : (
                <select
                  id="job"
                  value={selectedJobId}
                  onChange={(e) => setSelectedJobId(e.target.value)}
                  className="h-10 w-full rounded-lg border border-input bg-card px-3 text-sm text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <option value="">Select a job…</option>
                  {jobs.map((j) => (
                    <option key={j.id} value={j.id}>
                      {j.title} — {j.company}
                    </option>
                  ))}
                </select>
              )}
            </div>
          )}

          <div className="mt-5 flex items-center gap-3">
            <Button onClick={() => start.mutate()} disabled={!canStart} loading={start.isPending}>
              {!start.isPending && <Sparkles className="h-4 w-4" />}
              Start optimization
            </Button>
            <span className="text-xs text-muted-foreground">Runs Resume Intelligence → ATS → Approval → Export</span>
          </div>
        </Card>
      )}

      {/* Running / review / result phase */}
      {threadId && (
        <div className="grid gap-6 lg:grid-cols-[1fr_360px]">
          <div className="space-y-6">
            {/* ATS report — shown at the approval gate and after */}
            {(run?.status === 'INTERRUPTED' || run?.status === 'COMPLETED') && (
              <Card className="p-5">
                <div className="flex items-center justify-between">
                  <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground">
                    <Target className="h-4 w-4" /> ATS analysis
                  </h2>
                  {atsScore != null && (
                    <Badge tone={atsScore >= 75 ? 'success' : atsScore >= 50 ? 'primary' : 'warning'}>
                      ATS {atsScore}/100
                    </Badge>
                  )}
                </div>
                {missingKeywords.length > 0 && (
                  <div className="mt-3">
                    <p className="text-xs font-medium text-muted-foreground">Missing keywords</p>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      {missingKeywords.map((k) => (
                        <Badge key={k} tone="warning">{k}</Badge>
                      ))}
                    </div>
                  </div>
                )}
                {atsPlan.length > 0 && (
                  <div className="mt-3">
                    <p className="text-xs font-medium text-muted-foreground">Optimization plan</p>
                    <ul className="mt-1 space-y-1 text-sm text-foreground">
                      {atsPlan.map((step, i) => (
                        <li key={i} className="flex gap-2">
                          <span className="text-muted-foreground">{i + 1}.</span> {step}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </Card>
            )}

            {/* Approval gate */}
            {run?.status === 'INTERRUPTED' && (
              <Card className="border-warning/40 bg-warning/5 p-5">
                <h2 className="text-sm font-semibold text-foreground">Review &amp; approve</h2>
                <p className="mt-1 text-sm text-muted-foreground">
                  Approve to generate the optimized resume, or reject to discard.
                </p>
                <div className="mt-4 flex gap-2">
                  <Button onClick={() => decide.mutate('approved')} loading={decide.isPending} disabled={decide.isPending}>
                    <CheckCircle2 className="h-4 w-4" /> Approve &amp; generate
                  </Button>
                  <Button variant="outline" onClick={() => decide.mutate('rejected')} disabled={decide.isPending}>
                    <XCircle className="h-4 w-4" /> Reject
                  </Button>
                </div>
              </Card>
            )}

            {/* Result */}
            {run?.status === 'COMPLETED' && (
              <Card className="p-5">
                <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground">
                  <Sparkles className="h-4 w-4" /> Optimized resume
                </h2>
                {typeof optimized.executive_summary === 'string' && (
                  <p className="mt-3 text-sm text-foreground">{optimized.executive_summary as string}</p>
                )}
                {asStringList(optimized.professional_experience).length > 0 && (
                  <div className="mt-3">
                    <p className="text-xs font-medium text-muted-foreground">Professional experience</p>
                    <ul className="mt-1 space-y-1 text-sm text-foreground">
                      {asStringList(optimized.professional_experience).map((line, i) => (
                        <li key={i} className="flex gap-2"><span className="text-success">•</span> {line}</li>
                      ))}
                    </ul>
                  </div>
                )}
                {asStringList(optimized.skills_section).length > 0 && (
                  <div className="mt-3">
                    <p className="text-xs font-medium text-muted-foreground">Skills</p>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      {asStringList(optimized.skills_section).map((s) => (
                        <Badge key={s} tone="primary">{s}</Badge>
                      ))}
                    </div>
                  </div>
                )}
              </Card>
            )}

            {run?.status === 'REJECTED' && (
              <EmptyState icon={XCircle} title="Optimization rejected" description="No version was created. You can start a new optimization." />
            )}
            {run?.status === 'FAILED' && (
              <EmptyState icon={XCircle} title="Optimization failed" description="Something went wrong during the run. Please try again." />
            )}
          </div>

          {/* Sidebar: live timeline + version history */}
          <div className="space-y-6">
            <Card className="p-5">
              <h2 className="mb-3 text-sm font-semibold text-foreground">Workflow timeline</h2>
              <WorkflowStatusStepper workflowId={threadId} variant="vertical" />
            </Card>

            <Card className="p-5">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground">
                <History className="h-4 w-4" /> Version history
              </h2>
              {versions.length === 0 ? (
                <p className="text-sm text-muted-foreground">No optimized versions yet.</p>
              ) : (
                <VersionList versions={versions} onDownload={downloadVersion} />
              )}
            </Card>
          </div>
        </div>
      )}

      {/* Existing version history when first landing (no active run) */}
      {!threadId && versions.length > 0 && (
        <Card className="p-5">
          <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground">
            <History className="h-4 w-4" /> Version history
          </h2>
          <VersionList versions={versions} onDownload={downloadVersion} />
        </Card>
      )}
    </div>
  );
}

function VersionList({
  versions,
  onDownload,
}: {
  versions: ResumeVersion[];
  onDownload: (v: ResumeVersion, format: 'docx' | 'txt') => void;
}) {
  const modeLabel = useMemo(() => new Map(MODES.map((m) => [m.value, m.label])), []);
  return (
    <div className="space-y-3">
      {versions.map((v) => (
        <div key={v.id} className="rounded-lg border border-border p-3">
          <div className="flex items-center justify-between">
            <p className="text-sm font-medium text-foreground">
              v{v.versionNumber}
              <span className="ml-2 font-normal text-muted-foreground">
                {modeLabel.get((v.optimizationMode ?? '') as OptimizationMode) ?? v.optimizationMode ?? '—'}
              </span>
            </p>
            {v.atsAfter != null && (
              <Badge tone="success">
                ATS {v.atsBefore ?? '—'} → {v.atsAfter}
              </Badge>
            )}
          </div>
          <div className="mt-2 flex items-center gap-2">
            <Button size="sm" variant="outline" onClick={() => onDownload(v, 'docx')} disabled={!v.hasDownload}>
              <Download className="h-3.5 w-3.5" /> DOCX
            </Button>
            <Button size="sm" variant="ghost" onClick={() => onDownload(v, 'txt')}>
              <Download className="h-3.5 w-3.5" /> TXT
            </Button>
            {v.providerUsed && (
              <span className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
                <Briefcase className="h-3 w-3" /> {v.providerUsed}
              </span>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
